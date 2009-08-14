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
 * @author Arne Kepp, OpenGeo, Copyright 2009
 */
package org.geowebcache.filter.request;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

import javax.imageio.ImageIO;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.geowebcache.GeoWebCacheException;
import org.geowebcache.grid.GridSubSet;
import org.geowebcache.layer.TileLayer;
import org.geowebcache.layer.wms.WMSLayer;
import org.geowebcache.mime.ImageMime;
import org.geowebcache.util.ServletUtils;
import org.geowebcache.util.wms.BBOX;

public class WMSRasterFilter extends RasterFilter {
    private static Log log = LogFactory.getLog(RasterFilter.class);
    
    public String wmsLayers;

    public String wmsStyles;
    
    public Integer backendTimeout;
    
    protected BufferedImage loadMatrix(TileLayer tlayer, String gridSetId, int z) throws IOException, GeoWebCacheException {
        if(! (tlayer instanceof WMSLayer))
            return null;
        
        WMSLayer layer = (WMSLayer) tlayer;
        
        tlayer.isInitialized();
        
        GridSubSet gridSet = layer.getGridSubSet(gridSetId);
        
        int[] widthHeight = calculateWidthHeight(gridSet, z);
        
        String urlStr = wmsUrl(layer, gridSet, z, widthHeight);
        
        log.info("Updated WMS raster filter, zoom level " + z + " for " + getName() + " (" + layer.getName() +  ") , " + urlStr);
        
        URL wmsUrl = new URL(urlStr);
        
        HttpURLConnection conn = (HttpURLConnection) wmsUrl.openConnection();

        if(backendTimeout != null) {
            conn.setConnectTimeout(backendTimeout * 1000);
            conn.setReadTimeout(backendTimeout * 1000);
        } else {
            conn.setConnectTimeout(120000);
            conn.setReadTimeout(120000);
        }
        
        if(! conn.getContentType().startsWith("image/")) {
            throw new GeoWebCacheException("Unexpected response content type " + conn.getContentType() + " , request was " + urlStr + "\n");
        }
        
        if(conn.getResponseCode() != 200) {
            throw new GeoWebCacheException("Received response code " + conn.getResponseCode() + "\n");
        }
        
        byte[] ret = ServletUtils.readStream(conn.getInputStream(), 16384, 2048);
        
        InputStream is = new ByteArrayInputStream(ret);
        
        BufferedImage img = null;
        try {
            img = ImageIO.read(is);
        } catch (IOException ioe) {
            log.error(ioe.getMessage());
        }
        
        if(img.getWidth() != widthHeight[0] || img.getHeight() != widthHeight[1]) {
            String msg = "WMS raster filter has dimensions " + img.getWidth() + "," + img.getHeight()
                    + ", expected " + widthHeight[0] + "," + widthHeight[1] + "\n";
            throw new GeoWebCacheException(msg);
        }
        
        return img;
    }
        
    /**
     * Generates the URL used to create the lookup raster
     * 
     * @param layer
     * @param srs
     * @param z
     * @return
     */
    protected String wmsUrl(WMSLayer layer, GridSubSet gridSubSet, int z, int[] widthHeight) throws GeoWebCacheException {  
        BBOX bbox = gridSubSet.getCoverageBounds(z);
        
        StringBuilder str = new StringBuilder();
        str.append(layer.getWMSurl()[0]);
        str.append("SERVICE=WMS&REQUEST=getmap&VERSION=1.1.1");
        
        str.append("&LAYERS=");
        if(this.wmsLayers != null) {
            str.append(this.wmsLayers);
        } else {
             str.append(layer.getName());
        }
        
        str.append("&STYLES=");
        if(this.wmsStyles != null) {
            str.append(this.wmsStyles);
        }

        str.append("&BBOX=").append(bbox.toString());
        str.append("&WIDTH=").append(widthHeight[0]);
        str.append("&HEIGHT=").append(widthHeight[1]);
        str.append("&FORMAT=").append(ImageMime.tiff.getFormat());
        str.append("&FORMAT_OPTIONS=antialias:none");
        str.append("&BGCOLOR=0xFFFFFF");
        
        return str.toString();
    }

    public void update(byte[] filterData, TileLayer layer, String gridSetId, int z)
            throws GeoWebCacheException {
        throw new GeoWebCacheException("update(byte[] filterData, TileLayer layer, String gridSetId, int z) is not appropriate for WMSRasterFilters");
    }
    
    public void update(TileLayer layer, String gridSetId, int z)
    throws GeoWebCacheException {
        throw new GeoWebCacheException("TileLayer layer, String gridSetId, int z) is not appropriate for WMSRasterFilters");
    }
}
