/*******************************************************************************
* Copyright (c) 2013-2015 IBM Corp.
*
* Licensed under the Apache License, Version 2.0 (the "License");
* you may not use this file except in compliance with the License.
* You may obtain a copy of the License at
*
*    http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*******************************************************************************/
package com.acmeair.service;

import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;

import com.acmeair.AcmeAirConstants;
import com.acmeair.mongo.MongoConstants;

public abstract class FlightService implements AcmeAirConstants {
	protected FlightService(){
		if (useFlightDataRelatedCaching == null){
			Properties properties = new Properties();
			try {
				String acmeairProps = System.getenv("ACMEAIR_PROPERTIES");
				if(acmeairProps != null){
					System.out.println("Loading properties : " + acmeairProps);
					properties.load(new FileInputStream(acmeairProps));
					useFlightDataRelatedCaching = Boolean.parseBoolean(properties.getProperty("userFlightDataRelatedCaching"));
					System.out.println("useFlightDataRelatedCaching : " + useFlightDataRelatedCaching);
				}else {
					System.out.println("ACMEAIR_PROPERTIES environment variable is not set. Disabling Caching. To ensable caching, use Environment variable ACMEAIR_PROPERTIES");
					useFlightDataRelatedCaching = false;
				}
			} catch (Exception e) {
				System.out.println("ACMEAIR_PROPERTIES error. Check for below log");
				e.printStackTrace();
			}
		}
	}
	
	protected static Boolean useFlightDataRelatedCaching = null;
	protected static String acmeairDir = "";
	
	//TODO:need to find a way to invalidate these maps
	protected static ConcurrentHashMap<String, String> originAndDestPortToSegmentCache = new ConcurrentHashMap<String,String>();
	protected static ConcurrentHashMap<String, List<String>> flightSegmentAndDataToFlightCache = new ConcurrentHashMap<String,List<String>>();
	protected static ConcurrentHashMap<String, String> flightPKtoFlightCache = new ConcurrentHashMap<String, String>();
	
	

	public String getFlightByFlightId(String flightId, String flightSegment) {
		try {
			if(logger.isLoggable(Level.FINEST)){
				logger.finest("Book flights with "+ flightId + " and " + flightSegment);
			}
			if (useFlightDataRelatedCaching){
				String flight = flightPKtoFlightCache.get(flightId);
				if (flight == null) {				
					flight = getFlight(flightId, flightSegment);
					if (flightId != null && flight != null) {
						flightPKtoFlightCache.putIfAbsent(flightId, flight);
					}
				}
				return flight;
			}else {
				return getFlight(flightId, flightSegment);
			}
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}
	
	
	protected abstract String getFlight(String flightId, String flightSegment);
	
	public List<String> getFlightByAirportsAndDepartureDate(String fromAirport,	String toAirport, Date deptDate) {
		if(logger.isLoggable(Level.FINE)){
			logger.fine("Search for flights from "+ fromAirport + " to " + toAirport + " on " + deptDate.toString());
		}

		String originPortAndDestPortQueryString= fromAirport+toAirport;
		String segment = null;
		if (useFlightDataRelatedCaching){
			segment = originAndDestPortToSegmentCache.get(originPortAndDestPortQueryString);

			if (segment == null) {
				segment = getFlightSegment(fromAirport, toAirport);
				originAndDestPortToSegmentCache.putIfAbsent(originPortAndDestPortQueryString, segment);
			}		
		}else {
			segment = getFlightSegment(fromAirport, toAirport);
		}
		if(logger.isLoggable(Level.FINE)){
			logger.fine("flightsegment = "+ segment);
		}
		// cache flights that not available (checks against sentinel value above indirectly)
		try{
			if (segment == ""){
				return new ArrayList<String>();
			}
			JSONObject segmentJson = (JSONObject) new JSONParser().parse(segment);
			if(logger.isLoggable(Level.FINEST)){
				logger.finest("Segment in JSON "+ segmentJson);
			}
			String segId = (String)segmentJson.get("_id");
			if (segId == null) {
				if(logger.isLoggable(Level.FINEST)){
					logger.finest("Segment is null");
				}
				return new ArrayList<String>(); 
			}

			String flightSegmentIdAndScheduledDepartureTimeQueryString = segId + deptDate.toString();
			if(logger.isLoggable(Level.FINEST)){
				logger.finest("flightSegmentIdAndScheduledDepartureTimeQueryString "+ flightSegmentIdAndScheduledDepartureTimeQueryString);
			}
			if (useFlightDataRelatedCaching){
				List<String> flights = flightSegmentAndDataToFlightCache.get(flightSegmentIdAndScheduledDepartureTimeQueryString);
				if(logger.isLoggable(Level.FINE)){
					logger.fine("cache hit - flight search, key = "+ flightSegmentIdAndScheduledDepartureTimeQueryString);
				}
				if (flights == null) {				
					flights = getFlightBySegment(segment, deptDate);
					if(logger.isLoggable(Level.FINE)){
						logger.fine("cache miss - flight search, key = " + flightSegmentIdAndScheduledDepartureTimeQueryString + " flightCache size = " + flights.size());
					}

					flightSegmentAndDataToFlightCache.putIfAbsent(flightSegmentIdAndScheduledDepartureTimeQueryString, flights);
				}
				if(logger.isLoggable(Level.FINE))
					logger.fine("Returning "+ flights);
				return flights;
			}else {
				if(logger.isLoggable(Level.FINEST)){
					logger.finest("useFlightDataRelatedCaching is false ");
				}

				List<String> flights = getFlightBySegment(segment, deptDate);
				if(logger.isLoggable(Level.FINE))
					logger.fine("Returning "+ flights);
				return flights;
			}
		} catch (ParseException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
			return null;
		}
	}

	// NOTE:  This is not cached
	public List<String> getFlightByAirports(String fromAirport, String toAirport) {
			String segment = getFlightSegment(fromAirport, toAirport);
			if (segment == null) {
				return new ArrayList<String>(); 
			}	
			return getFlightBySegment(segment, null);
	}
	
	protected abstract String getFlightSegment(String fromAirport, String toAirport);
	
	protected abstract List<String> getFlightBySegment(String segment, Date deptDate);  
			
	public abstract void storeAirportMapping(String mapping);

	public abstract void createAirportCodeMapping(String airportCode, String airportName);
	
	public abstract void createNewFlight(String flightSegmentId,
			Date scheduledDepartureTime, Date scheduledArrivalTime,
			int firstClassBaseCost, int economyClassBaseCost,
			int numFirstClassSeats, int numEconomyClassSeats,
			String airplaneTypeId);

	public abstract void storeFlightSegment(String flightSeg);
	
	public abstract void storeFlightSegment(String flightName, String origPort, String destPort, String miles);
	
	public abstract Long countFlightSegments();
	
	public abstract Long countFlights();
	
	public abstract Long countAirports();

	public abstract void dropDB();
	
}