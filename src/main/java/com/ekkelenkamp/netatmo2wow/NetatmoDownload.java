package com.ekkelenkamp.netatmo2wow;

import com.ekkelenkamp.netatmo2wow.model.Device;
import com.ekkelenkamp.netatmo2wow.model.Measures;
import org.apache.log4j.Logger;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;

import java.io.IOException;
import java.net.URL;
import java.util.*;

public class NetatmoDownload {

    private NetatmoHttpClient netatmoHttpClient;

    final static Logger logger = Logger.getLogger(NetatmoDownload.class);
    final static long TIME_STEP_TOLERANCE = 2 * 60 * 1000;

    // API URLs that will be used for requests, see: http://dev.netatmo.com/doc/restapi.
    protected final String URL_BASE = "https://api.netatmo.net";
    protected final String URL_REQUEST_TOKEN = URL_BASE + "/oauth2/token";
    //protected final String URL_GET_DEVICES_LIST = URL_BASE + "/api/devicelist";
    protected final String URL_GET_MEASURES_LIST = URL_BASE + "/api/getmeasure";
    protected final String URL_GET_STATION_DATA = URL_BASE + "/api/getstationsdata";

    public NetatmoDownload(NetatmoHttpClient netatmoHttpClient) {
        this.netatmoHttpClient = netatmoHttpClient;
    }

    public List<Measures> downloadMeasures(String username, String password, String clientId, String clientSecret, String timespan) throws IOException {
        String url = URL_REQUEST_TOKEN;
        String token = login(username, password, clientId, clientSecret);
        logger.debug("Token: " + token);
        
        String scale = "max";
        long timePeriod = Long.parseLong(timespan);
        // netatmo calculates in seconds, not milliseconds.
        
        long currentDate = ((new java.util.Date().getTime()) / 1000) - timePeriod;
        logger.debug("start time: " + new Date(currentDate * 1000));
        logger.debug("start time seconds: " + currentDate);
        
        Device device = getDevices(token);
        List<Measures> measures = new ArrayList<Measures>();       
        Map<String, List<String>> devices = device.getDevices();
        
        Double accumulatedRain = 0.0;
        for (String dev : devices.keySet()) 
        {
        	measures.addAll(getMeasures(token, dev, null, "Pressure" , scale, currentDate, ""));
            List<String> modules = devices.get(dev);
            
            for (String module : modules) 
            {
                logger.debug("Device: " + device);
                logger.debug("Module: " + module);

                String moduleMeasureTypes = device.getModuleDataType(module);
                
                if (moduleMeasureTypes.equals("Rain"))
                {
                    List<Measures> accumRain = 
                    		getMeasures(token, dev, module, "sum_rain", "1day", currentDate, "last");
                    
                    if (accumRain.size() > 0)
                    {
                    	accumulatedRain = accumRain.get(0).getRainAccumulated();
                    }
                }

                List<Measures> newMeasures = getMeasures(token, dev, module, moduleMeasureTypes, scale, currentDate, "");
                measures = mergeMeasures(measures, newMeasures, TIME_STEP_TOLERANCE);
            }
        }
        
        Collections.sort(measures);
        calculateAccumulativeRainfail(measures);
        
        if (measures.size() > 0)
        {
        	measures.get(measures.size() - 1).setRainAccumulated(accumulatedRain);
        }
        
        return measures;
    }

    private void calculateAccumulativeRainfail(List<Measures> measures) 
    {
        for (int i = measures.size() - 1; i > 0; i--) 
        {
            Measures latestMeasure = measures.get(i);
            // now get all measures before and including this one until we accumulated 1 hour of rainfall.
            Long start = latestMeasure.getTimestamp();
            Long hourDif = (long) 1000 * 60 * 60; // 1 hour.
            Double accumulatedRainfall = 0.0;
            for (int j = i; j > 0 && latestMeasure.getRain() != null; j--) 
            {
                Measures currentMeasure = measures.get(j);
                if (start - currentMeasure.getTimestamp() < hourDif) 
                {
                    // no hour passed yet.
                    accumulatedRainfall += currentMeasure.getRain();
                } 
                else 
                {
                    latestMeasure.setRainLastHour(accumulatedRainfall);
                    break;
                }
            }
        }
    }

    /**
     * Merge existing measures with new measures.
     * A measure is merged of the timestamps differ less than 2 minutes (since netatmo takes a measure every 5 minutes)
     * During a merge, the the value of the most recent measurement is taken, if available.
     *
     * @param measures
     * @param newMeasures
     * @return
     */
    public List<Measures> mergeMeasures(List<Measures> measures, List<Measures> newMeasuresList, long timestepTolerance) {

        List<Measures> result = new ArrayList<Measures>();

        List<Measures> newMeasures = new ArrayList<Measures>();
        for (Measures n : newMeasuresList) 
        {
            boolean mergedMeasure = false;
            for (Measures m : measures) 
            {
                if (Math.abs(m.getTimestamp() - n.getTimestamp()) < timestepTolerance) 
                {
                    n.merge(m);
                    mergedMeasure = true;
                    continue;
                }
            }
            if (mergedMeasure) 
            {
                result.add(n);
            }
        }
        return result;


    }

    public List<Measures> getMeasures(String token, String device, String module, String measureTypes, String scale, long dateBegin, String dateEnd) {
        HashMap<String, String> params = new HashMap<String, String>();
        params.put("access_token", token);
        params.put("device_id", device);
        if (module != null) 
        {
            params.put("module_id", module);
        }
        params.put("type", measureTypes);
        params.put("scale", scale);
        
        if (dateEnd == "last")
        {
        	params.put("date_end", "" + dateEnd);
        }
        
        params.put("date_begin", "" + dateBegin);        	
        params.put("optimize", "false"); // easy parsing.

        List<Measures> measuresList = new ArrayList<Measures>();
        try 
        {
            JSONParser parser = new JSONParser();
            String result = netatmoHttpClient.post(new URL(URL_GET_MEASURES_LIST), params);
            Object obj = parser.parse(result);
            JSONObject jsonResult = (JSONObject) obj;
            if (!(jsonResult.get("body") instanceof JSONObject)) 
            {
                logger.info("No data found");
                return measuresList;
            }
            JSONObject body = (JSONObject) jsonResult.get("body");

            for (Object o: body.keySet()) 
            {
                String timeStamp = (String) o;
                JSONArray valuesArray = (JSONArray) body.get(timeStamp);
                Measures measures = new Measures();
                long times = Long.parseLong(timeStamp) * 1000;
                measures.setTimestamp(times);
                
                if (measureTypes.equals("Pressure") && valuesArray.get(0) != null) 
                {
                    measures.setPressure(Double.parseDouble("" + valuesArray.get(0)));
                } 
                else if (measureTypes.equals("Rain"))
                {
                	measures.setRain(Double.parseDouble("" + valuesArray.get(0)));
                }
                else if (measureTypes.equals("sum_rain"))
        		{
            		measures.setRainAccumulated(Double.parseDouble("" + valuesArray.get(0)));	
	    		}
                else if (measureTypes.equals("Temperature,Humidity"))
                {
                	measures.setTemperature(Double.parseDouble("" + valuesArray.get(0)));
                	measures.setHumidity(Double.parseDouble("" + valuesArray.get(1)));
                }
                else if (measureTypes.equals("WindStrength,WindAngle,GustStrength,GustAngle"))
                {
                	measures.setWind(Double.parseDouble("" + valuesArray.get(0)),
                			Double.parseDouble("" + valuesArray.get(1)),
        					Double.parseDouble("" + valuesArray.get(2)),
							Double.parseDouble("" + valuesArray.get(3)));
                }
                
                measuresList.add(measures);
            }

            return measuresList;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public Device getDevices(String token) {
        Device device = new Device();
        HashMap<String, String> params = new HashMap<String, String>();
        params.put("access_token",token);
        
        //List<String> devicesList = new ArrayList<String>();
        try 
        {
            JSONParser parser = new JSONParser();
            String result = netatmoHttpClient.post(new URL(URL_GET_STATION_DATA), params);
            Object obj = parser.parse(result);
            JSONObject jsonResult = (JSONObject) obj;
            JSONObject body = (JSONObject) jsonResult.get("body");
            JSONArray devices = (JSONArray) body.get("devices");
            if (devices.size() > 0)
            {            	
            	JSONObject firstDevice = (JSONObject) devices.get(0);            
            	String deviceId = (String) firstDevice.get("_id");            	
            	JSONArray modules = (JSONArray) firstDevice.get("modules");
            	
            	for (int i = 0; i < modules.size(); i++) 
            	{
            		JSONObject module = (JSONObject) modules.get(i);
            		String moduleId = (String) module.get("_id");
            		JSONArray dataTypes = (JSONArray) module.get("data_type");
            		if (dataTypes.size() > 0) 
            		{
            			String joinedDataTypes = String.join(",", dataTypes);  
            			if (joinedDataTypes.equals("Wind"))
            			{
            				joinedDataTypes = "WindStrength,WindAngle,GustStrength,GustAngle";
            			}
                        device.addModuleToDevice(deviceId, moduleId, joinedDataTypes);
            		}
            	}
            }            

            return device;
        } 
        catch (Exception e) 
        {
            throw new RuntimeException(e);
        }
    }

    /**
     * This is the first request you have to do before being able to use the API.
     * It allows you to retrieve an access token in one step,
     * using your application's credentials and the user's credentials.
     */
    public String login(String email, String password, String clientId, String clientSecret) {
        HashMap<String, String> params = new HashMap<String, String>();
        params.put("grant_type", "password");
        params.put("client_id", clientId);
        params.put("client_secret", clientSecret);
        params.put("username", email);
        params.put("password", password);
        params.put("scope", "read_station");
        try {
            JSONParser parser = new JSONParser();
            String result = netatmoHttpClient.post(new URL(URL_REQUEST_TOKEN), params);
            Object obj = parser.parse(result);
            JSONObject jsonResult = (JSONObject) obj;
            String token = (String) jsonResult.get("access_token");
            return token;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
