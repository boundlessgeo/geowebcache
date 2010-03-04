/**
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU Lesser General Public License
 *  along with this program.  If not, see <http://www.gnu.org/licenses/>.
 * 
 * @author Arne Kepp and Gabriel Roldan (OpenGeo) 2010
 *  
 */
package org.geowebcache.georss;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.util.Date;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;

import javax.imageio.ImageIO;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.geotools.feature.type.DateUtil;
import org.geowebcache.GeoWebCacheException;
import org.geowebcache.grid.GridSubset;
import org.geowebcache.layer.TileLayer;
import org.geowebcache.layer.updatesource.GeoRSSFeedDefinition;
import org.geowebcache.mime.MimeException;
import org.geowebcache.mime.MimeType;
import org.geowebcache.rest.GWCTask;
import org.geowebcache.rest.GWCTask.STATE;
import org.geowebcache.rest.seed.RasterMask;
import org.geowebcache.rest.seed.SeedRestlet;
import org.geowebcache.storage.DiscontinuousTileRange;

/**
 * A task to run a GeoRSS feed poll and launch the seeding process
 * <p>
 * If a poll {@link GeoRSSFeedDefinition#getFeedUrl() URL} is configured with the
 * <code>${lastUpdate}</code> then the formatted date and time for the last updated entry will be
 * passed on to the URL.
 * </p>
 * <p>
 * For example, if the URL is configured as
 * <code>http://some.server.org/georss/gwcupdates?updateSequence=${lastUpdate}</code> and a previous
 * poll for this resource determined that the most recent <code>updated</code> property of a GeoRSS
 * entry was <code>2010-01-17T01:05:32Z</code>, the the resulting feed URL will be
 * <code>http://some.server.org/georss/gwcupdates?updateSequence=2010-01-17T01:05:32Z</code> (or its
 * equivalent in the server's time zone).
 * </p>
 * <p>
 * By the other hand, if <code>${lastUpdate}</code> parameter is configured but this task is going
 * to perform the first poll (hence there's no last update history), the parameter will be replaced
 * by the empty string, resulting in something like
 * <code>http://some.server.org/georss/gwcupdates?updateSequence=</code>
 * </p>
 */
class GeoRSSPollTask implements Runnable {

    private static final Log logger = LogFactory.getLog(GeoRSSPollTask.class);

    private static final String LAST_UPDATE_URL_TEMPLATE = "${lastUpdate}";

    private final PollDef poll;

    private final SeedRestlet seedRestlet;
    
    private LinkedList<GWCTask> seedTasks = new LinkedList<GWCTask>();

    /**
     * Date and time of the more recent GeoRSS entry updated property. To be used as parameter for
     * the feed url
     */
    private String lastUpdatedEntry = "";

    public GeoRSSPollTask(final PollDef poll, final SeedRestlet seedRestlet) {
        this.poll = poll;
        this.seedRestlet = seedRestlet;
    }

    /**
     * Called by the thread executor when the poll def's interval has elapsed (or as soon as
     * possible after it elapsed).
     */
    public void run() {
        /*
         * This method cannot throw an exception or the thread scheduler will discard the task.
         * Instead, if an error happens when polling we log the exception and hope for the next run
         * to work?
         */
        try {
            runPollAndLaunchSeed();
        } catch (Exception e) {
            logger.error("Error encountered trying to poll the GeoRSS feed "
                    + poll.getPollDef().getFeedUrl()
                    + ". Another attempt will be made after the poll interval of "
                    + poll.getPollDef().getPollIntervalStr(), e);
            
        } catch (OutOfMemoryError error) {
            System.gc();
            logger.fatal("Out of memory error processing poll " + poll.getPollDef()
                    + ". Need to reduce the maxMaskLevel param or increase system memory."
                    + " Poll disabled.", error);
            throw error;
        }
    }

    private void runPollAndLaunchSeed() throws IOException {
        final TileLayer layer = poll.getLayer();
        final GeoRSSFeedDefinition pollDef = poll.getPollDef();

        logger.info("Polling GeoRSS feed for layer " + layer.getName() + ": " + pollDef.toString());

        final String gridSetId = pollDef.getGridSetId();
        final URL feedUrl = new URL(templateFeedUrl(pollDef.getFeedUrl()));

        logger.debug("Getting GeoRSS reader for " + feedUrl.toExternalForm());
        final GeoRSSReaderFactory geoRSSReaderFactory = new GeoRSSReaderFactory();
        
        GeoRSSReader geoRSSReader = null;
        try {
            geoRSSReader = geoRSSReaderFactory.createReader(feedUrl);
        } catch(IOException ioe) {
            logger.error("Failed to fetch RSS feed from " + feedUrl + "\n" + ioe.getMessage());
            return;
        }

        logger.debug("Got reader for " + pollDef.getFeedUrl()
                + ". Creating geometry filter matrix for gridset " + gridSetId + " on layer "
                + layer.getName());

        final int maxMaskLevel = pollDef.getMaxMaskLevel();
        final GeoRSSTileRangeBuilder matrixBuilder = new GeoRSSTileRangeBuilder(layer, gridSetId,
                maxMaskLevel);

        logger.debug("Creating tile range mask based on GeoRSS feed's geometries from "
                + feedUrl.toExternalForm() + " for " + layer.getName());

        final TileGridFilterMatrix tileRangeMask = matrixBuilder.buildTileRangeMask(geoRSSReader, this.lastUpdatedEntry);
      
        if(tileRangeMask == null) {
            logger.info("Did not create a tileRangeMask, presumably no new entries in feed.");
            return;
        }
        
        this.lastUpdatedEntry = matrixBuilder.getLastEntryUpdate();
        
        logger.debug("Created tile range mask based on GeoRSS geometry feed from " + pollDef
                + " for " + layer.getName() + ". Calculating number of affected tiles...");
        _logImagesToDisk(tileRangeMask);

        final boolean tilesAffected = tileRangeMask.hasTilesSet();
        if (tilesAffected) {
            logger.info("Launching reseed process " + pollDef + " for " + layer.getName());
        } else {
            logger.info(pollDef + " for " + layer.getName()
                    + " did not affect any tile. No need to reseed.");
            return;
        }

        launchSeeding(layer, pollDef, gridSetId, tileRangeMask);

        logger.info("Seeding process for tiles affected by feed " + feedUrl.toExternalForm()
                + " successfully launched.");
    }

    private String templateFeedUrl(final String feedUrl) {
        if (feedUrl == null) {
            throw new NullPointerException("feedUrl");
        }

        String url = feedUrl;
        if (feedUrl.indexOf(LAST_UPDATE_URL_TEMPLATE) > -1) {
            url = feedUrl.replace(LAST_UPDATE_URL_TEMPLATE, lastUpdatedEntry);
            logger.info("Feed URL templated as '" + url + "'");
        }
        return url;
    }

    /**
     * For debug purposes only, writes down the bitmask images to the directory specified by the
     * System property (ej, {@code -Dorg.geowebcache.georss.debugToDisk=target/})
     * 
     * @param tileRangeMask
     */
    private void _logImagesToDisk(final TileGridFilterMatrix matrix) {
        if (null == System.getProperty("org.geowebcache.georss.debugToDisk")) {
            return;
        }
        File target = new File(System.getProperty("org.geowebcache.georss.debugToDisk"));
        if (!target.isDirectory() || !target.canWrite()) {
            throw new IllegalStateException("Can't access debug directory for "
                    + "dumping mask images: " + target.getAbsolutePath());
        }

        logger.warn("\n!!!!!!!!!!!\n REMEMBER NOT TO SET THE org.geowebcache.georss.debugToDisk"
                + " SYSTEM PROPERTY ON A PRODUCTION ENVIRONMENT \n!!!!!!!!!!!");
        BufferedImage[] byLevelMasks = matrix.getByLevelMasks();

        for (int i = 0; i < byLevelMasks.length; i++) {
            File output = new File(target, poll.getLayer().getName() + "_level_" + i + ".tiff");
            System.out.println("--- writing " + output.getAbsolutePath() + "---");
            try {
                ImageIO.write(byLevelMasks[i], "TIFF", output);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    private void launchSeeding(final TileLayer layer, final GeoRSSFeedDefinition pollDef,
            final String gridSetId, final TileGridFilterMatrix tileRangeMask) {
        
        GridSubset gridSub = layer.getGridSubset(gridSetId);

        long[][] fullCoverage = gridSub.getCoverages();
        long[][] coveredBounds = tileRangeMask.getCoveredBounds();

        BufferedImage[] byLevelMasks = tileRangeMask.getByLevelMasks();

        RasterMask rasterMask = new RasterMask(byLevelMasks, fullCoverage, coveredBounds);
        
        List<MimeType> mimeList = null;
        
        if(pollDef.getFormat() != null) {
            MimeType mime;
            try {
                mime = MimeType.createFromFormat(pollDef.getFormat());
                mimeList = new LinkedList<MimeType>();
                mimeList.add(mime);
            } catch (MimeException e) {
                logger.error(e.getMessage());
            }
        }
        
        if(mimeList == null) {
            mimeList = layer.getMimeTypes();
        }
        
        Iterator<MimeType> mimeIter = mimeList.iterator();
        
        // Ask any existing seed jobs started by this feed to terminate
        stopSeeding(true);

        // We do the truncate synchronously to get rid of stale data as quickly as we can
        while(mimeIter.hasNext()) {
            DiscontinuousTileRange dtr = new DiscontinuousTileRange(layer.getName(), gridSetId, 
                    gridSub.getZoomStart(), gridSub.getZoomStop(), rasterMask, mimeIter.next(), null);
            try {
                GWCTask[] tasks = seedRestlet.createTasks(dtr, layer, GWCTask.TYPE.TRUNCATE, 1, false);
                tasks[0].doAction();
            } catch (GeoWebCacheException e) {
                logger.error("Problem truncating based on GeoRSS feed: " + e.getMessage());
            }
        }
        
        // If truncate was all that was needed, we can quit now
        if(pollDef.getOperation() == GWCTask.TYPE.TRUNCATE) {
            logger.info("Truncation succeeded, won't seed as stated by poll def: " + pollDef);
            return;
        }
        
        // ... else we seed
        mimeIter = mimeList.iterator();
        while(mimeIter.hasNext()) {
            DiscontinuousTileRange dtr = new DiscontinuousTileRange(layer.getName(), gridSetId, 
                    gridSub.getZoomStart(), gridSub.getZoomStop(), rasterMask, mimeIter.next(), null);
            
            final int seedingThreads = pollDef.getSeedingThreads();
            GWCTask[] tasks = seedRestlet.createTasks(dtr, layer, GWCTask.TYPE.SEED, seedingThreads, false);
            seedRestlet.dispatchTasks(tasks);
            
            // Save the handles so we can stop them
            for (GWCTask task : tasks) {
                seedTasks.add(task);
            }

        }
    }
    
    protected void stopSeeding(boolean checkLiveCount) {
        if(this.seedTasks != null) {
            int liveCount = 0;
            for (GWCTask task : seedTasks) {
                if(task.getState() != STATE.DEAD && task.getState() != STATE.DONE) {
                    task.terminateNicely();
                    liveCount++;
                }
            }
            
            Thread.yield();

            for (GWCTask task : seedTasks) {
                if(task.getState() != STATE.DEAD && task.getState() != STATE.DONE) {
                    liveCount++;
                }
            }
            
            if(! checkLiveCount || liveCount == 0) {
                return;
            }
            
            try {
                logger.debug("Found " + liveCount + " running seed threads. Waiting 3s for them to terminate.");
                Thread.sleep(3000);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            
            liveCount = 0;
            Iterator<GWCTask> iter = seedTasks.iterator();
            while(iter.hasNext()) {
                GWCTask task = iter.next();
                if(task.getState() != STATE.DEAD && task.getState() != STATE.DONE) {
                    liveCount++;  
                } else {
                    iter.remove();
                }
            }
            if(liveCount > 0) {
                logger.info(liveCount + " seed jobs are still waiting to terminate, proceeding anyway.");
            }

        } else {
            logger.debug("Found no running seed jobs");
        }
    }
}