package com.adobe.communities.ugc.migration.importer;


import com.adobe.communities.ugc.migration.util.Constants;
import com.adobe.cq.social.activitystreams.api.SocialActivityManager;
import com.adobe.cq.social.activitystreams.listener.api.ActivityStreamProvider;
import com.adobe.granite.activitystreams.ActivityException;
import org.apache.felix.scr.annotations.*;
import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.SlingHttpServletResponse;
import org.apache.sling.api.request.RequestParameter;
import org.apache.sling.api.request.RequestParameterMap;
import org.apache.sling.commons.json.JSONArray;
import org.apache.sling.commons.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

@Component(label = "UGC Migration Activity Importer",
        description = "Accepts a json file containing activity data and applies it to stored  profiles",
        specVersion = "1.1")
@Service
@Properties({@Property(name = "sling.servlet.paths", value = "/services/social/activity/import")})
public class ActivityImportServlet extends UGCImport {

    @Reference
    private SocialActivityManager activityManager;

    @Reference(target = "(component.name=com.adobe.cq.social.activitystreams.listener.impl.ResourceActivityStreamProviderFactory)")
    private ActivityStreamProvider streamProvider;

    private static final Logger logger = LoggerFactory.getLogger(ActivityImportServlet.class);

    protected void doPost( final SlingHttpServletRequest request, final SlingHttpServletResponse response){

        RequestParameterMap paramMap = request.getRequestParameterMap();

        if(paramMap.size()<3){
            throw new ActivityException("Unable to import activities, missing input data");
        }

        //read meta data
        RequestParameter metaFileParam = paramMap.getValue(Constants.ID_MAPPING_FILE);
        Map<String, Map<String, String>> idMap = loadKeyMetaInfo(metaFileParam);

        RequestParameter importCountFile = paramMap.getValue(Constants.TO_IMPORT_FILE);
        List<Integer> toImportActivity = loadSkippedMetaInfo(importCountFile);

        //read exported data
        RequestParameter dataFile = paramMap.getValue(Constants.DATA_FILE);
        String jsonBody = loadData(dataFile);

        RequestParameter startParam = paramMap.getValue(Constants.START);
        final int start  = startParam !=null ?Integer.parseInt(startParam.getString()):0;

        JSONObject json ;
        try {
            json = new JSONObject(jsonBody);
            JSONArray activities = json.optJSONArray(Constants.ACTIVITIES) != null
                    ? json.optJSONArray(Constants.ACTIVITIES)
                    : new JSONArray();

            importUGC(activities,streamProvider, activityManager, idMap, toImportActivity, start, "Activities");
        } catch (Exception e) {
            logger.error("Error during activity import", e);
        }
    }

}