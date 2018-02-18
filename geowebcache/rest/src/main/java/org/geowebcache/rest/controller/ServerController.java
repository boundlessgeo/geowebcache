package org.geowebcache.rest.controller;

import org.geowebcache.config.ServerConfiguration;
import org.geowebcache.rest.converter.ServerConfigurationPOJO;
import org.geowebcache.rest.exception.RestException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;

@Component
@RestController
@RequestMapping(path="${gwc.context.suffix:}/rest/global")
public class ServerController {
    @Autowired
    ServerConfiguration serverConfiguration;

    //TODO: Can this be consolidated across controllers?
    @ExceptionHandler(RestException.class)
    public ResponseEntity<?> handleRestException(RestException ex) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.TEXT_PLAIN);
        return new ResponseEntity<Object>(ex.toString(), headers, ex.getStatus());
    }

    @RequestMapping(method = RequestMethod.GET)
    public ServerConfiguration serverGet() throws IOException {
        return new ServerConfigurationPOJO(serverConfiguration);
    }

    @RequestMapping(method = RequestMethod.PUT)
    public void serverPut(@RequestBody ServerConfigurationPOJO serverConfiguration) throws IOException {

        //Throw an exception if a read-only value is modified
        if (serverConfiguration.getIdentifier() != null &&
                !this.serverConfiguration.getIdentifier().equals(serverConfiguration.getIdentifier())) {
            throw new RestException("Cannot modify read-only property \"identifier\"", HttpStatus.BAD_REQUEST);
        }
        if (serverConfiguration.getLocation() != null &&
                !this.serverConfiguration.getLocation().equals(serverConfiguration.getLocation())) {
            throw new RestException("Cannot modify read-only property \"location\"", HttpStatus.BAD_REQUEST);
        }
        if (serverConfiguration.getVersion() != null &&
                !this.serverConfiguration.getVersion().equals(serverConfiguration.getVersion())) {
            throw new RestException("Cannot modify read-only property \"version\"", HttpStatus.BAD_REQUEST);
        }

        serverConfiguration.apply(this.serverConfiguration);
    }
}