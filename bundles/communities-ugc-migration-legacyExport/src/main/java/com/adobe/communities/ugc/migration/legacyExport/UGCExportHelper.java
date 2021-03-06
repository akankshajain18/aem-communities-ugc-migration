/*************************************************************************
 *
 * ADOBE SYSTEMS INCORPORATED
 * Copyright 2015 Adobe Systems Incorporated
 * All Rights Reserved.
 *
 * NOTICE:  Adobe permits you to use, modify, and distribute this file in accordance with the
 * terms of the Adobe license agreement accompanying it.  If you have received this file from a
 * source other than Adobe, then your use, modification, or distribution of it requires the prior
 * written permission of Adobe.
 **************************************************************************/
package com.adobe.communities.ugc.migration.legacyExport;

import static com.adobe.cq.social.tally.TallyConstants.RESPONSE_PROPERTY;
import static com.adobe.cq.social.tally.TallyConstants.TIMESTAMP_PROPERTY;

import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.io.Writer;
import java.net.URLEncoder;
import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import javax.jcr.Node;
import javax.jcr.NodeIterator;
import javax.jcr.RepositoryException;

import org.apache.commons.codec.binary.Base64;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ValueMap;
import org.apache.sling.commons.json.JSONArray;
import org.apache.sling.commons.json.JSONException;
import org.apache.sling.commons.json.io.JSONWriter;
import org.apache.sling.jcr.resource.JcrResourceConstants;

import com.adobe.cq.social.commons.Comment;
import com.adobe.cq.social.forum.api.Post;
import com.adobe.cq.social.journal.JournalEntry;
import com.adobe.cq.social.storage.buckets.NestedBucketStorageSystem;
import com.adobe.cq.social.tally.PollResponse;
import com.adobe.cq.social.tally.ResponseValue;
import com.adobe.cq.social.tally.TallyConstants;

public class UGCExportHelper {

    private final static int DATA_ENCODING_CHUNK_SIZE = 1440;

    public static void extractSubNode(JSONWriter object, final Resource node) throws JSONException {
        final ValueMap childVm = node.adaptTo(ValueMap.class);
        extractProperties(object, childVm);
        final Iterable<Resource> childNodes = node.getChildren();
        if (childNodes != null) {
            object.key(ContentTypeDefinitions.LABEL_SUBNODES);
            object.object();
            for (final Resource subNode : childNodes) {
                object.key(subNode.getName());
                JSONWriter subObject = object.object();
                extractSubNode(subObject, subNode);
                object.endObject();
            }
            object.endObject();
        }
    }

    public static void extractAttachment(final Writer ioWriter, final JSONWriter writer, final Resource node)
        throws JSONException, UnsupportedEncodingException {
        Resource contentNode = node.getChild("jcr:content");
        if (contentNode == null) {
            writer.key(ContentTypeDefinitions.LABEL_ERROR);
            writer.value("provided resource was not an attachment - no content node beneath " + node.getPath());
            return;
        }
        ValueMap content = contentNode.adaptTo(ValueMap.class);
        if (!content.containsKey("jcr:mimeType") || !content.containsKey("jcr:data")) {
            writer.key(ContentTypeDefinitions.LABEL_ERROR);
            writer.value("provided resource was not an attachment - content node contained no attachment data under "
            + node.getPath());
            return;
        }
        writer.key("filename");
        writer.value(URLEncoder.encode(node.getName(), "UTF-8"));
        writer.key("jcr:mimeType");
        writer.value(content.get("jcr:mimeType"));

        try {
            ioWriter.write(",\"jcr:data\":\"");
            final InputStream data = (InputStream) content.get("jcr:data");
            byte[] byteData = new byte[DATA_ENCODING_CHUNK_SIZE];
            int read = 0;
            while (read != -1) {
                read = data.read(byteData);
                if (read > 0 && read < DATA_ENCODING_CHUNK_SIZE) {
                    // make a right-size container for the byte data actually read
                    byte[] byteArray = new byte[read];
                    System.arraycopy(byteData, 0, byteArray, 0, read);
                    byte[] encodedBytes = Base64.encodeBase64(byteArray);
                    ioWriter.write(new String(encodedBytes));
                } else if (read == DATA_ENCODING_CHUNK_SIZE) {
                    byte[] encodedBytes = Base64.encodeBase64(byteData);
                    ioWriter.write(new String(encodedBytes));
                }
            }
            ioWriter.write("\"");
        } catch (IOException e) {
            writer.key(ContentTypeDefinitions.LABEL_ERROR);
            writer.value("IOException while getting attachment at " + node.getPath() + ": " + e.getMessage());
        }
    }

    protected static Map<String, Map<Long, ResponseValue>> getTallyResponses(Resource tallyResource)
            throws JSONException{
        Map<String, Map<Long, ResponseValue>> returnValue = new HashMap<String, Map<Long, ResponseValue>>();
        final ResourceResolver resolver = tallyResource.getResourceResolver();
        final String tallyPath = tallyResource.getPath();
        if (!tallyPath.startsWith("/content/usergenerated")) {
            tallyResource = resolver.resolve("/content/usergenerated" + tallyPath);
        }
        final Resource responsesNode = tallyResource.getChild(TallyConstants.RESPONSES_PATH);
        if (responsesNode == null) {
            return null;
        }
        NestedBucketStorageSystem bucketSystem = getBucketSystem(responsesNode);
        if (null == bucketSystem) {
            return null;
        }

        final Iterator<Resource> buckets = bucketSystem.listBuckets();
        while (buckets.hasNext()) {
            final Resource bucketResource = buckets.next();
            final Node bucketNode = bucketResource.adaptTo(Node.class);
            try {
                final NodeIterator userNodesInBucket = bucketNode.getNodes();
                while (userNodesInBucket.hasNext()) {
                    final Node userNode = userNodesInBucket.nextNode();
                    final NestedBucketStorageSystem userBucketSystem =
                        getBucketSystem(resolver.getResource(userNode.getPath()));
                    final Iterator<Resource> userBuckets = userBucketSystem.listBuckets();
                    final Map<Long, ResponseValue> userReturnValue = new HashMap<Long, ResponseValue>();
                    while (userBuckets.hasNext()) {
                        final NodeIterator userResponses = userBuckets.next().adaptTo(Node.class).getNodes();
                        while (userResponses.hasNext()) {
                            final Node responseNode = userResponses.nextNode();
                            final Long responseTimestamp = responseNode.getProperty(TIMESTAMP_PROPERTY).getLong();
                            userReturnValue.put(responseTimestamp,
                                new PollResponse(responseNode.getProperty(RESPONSE_PROPERTY).getString()));
                        }
                    }
                    returnValue.put(userNode.getName(), userReturnValue);
                }
            } catch (final RepositoryException e) {
                throw new JSONException("Error trying to read user responses from bucket in "
                        + bucketResource.getPath(), e);
            }
        }
        return returnValue;
    }

    /**
     * Need to do this because the responses that tally returns don't actually link users with their response values.
     * In order to do an export under the legacy code, we have to explicitly handle the bucketing system.
     * @param resource - the resource being exported
     * @return NestedBucketStorageSystem
     */
    protected static NestedBucketStorageSystem getBucketSystem(final Resource resource) {
        if (null == resource) {
            return null;
        }
        NestedBucketStorageSystem bucketSystem;
        bucketSystem = resource.adaptTo(NestedBucketStorageSystem.class);
        if (bucketSystem != null) {
            bucketSystem.setBucketPostfix(NestedBucketStorageSystem.DEFAULT_BUCKET_POSTFIX);
        }
        return bucketSystem;
    }

    public static void extractTally(final JSONWriter responseArray, final Resource rootNode, final String tallyType)
        throws JSONException, UnsupportedEncodingException {

        final Map<String, Map<Long, ResponseValue>> responses = getTallyResponses(rootNode);
        if (null != responses) {
            for (final String userIdentifier : responses.keySet()) {
                for (final Map.Entry<Long, ResponseValue> entry : responses.get(userIdentifier).entrySet()) {
                    final JSONWriter voteObject = responseArray.object();
                    final String response = entry.getValue().getResponseValue();
                    voteObject.key("timestamp");
                    voteObject.value(entry.getKey());
                    voteObject.key("response");
                    voteObject.value(URLEncoder.encode(response, "UTF-8"));
                    voteObject.key("userIdentifier");
                    voteObject.value(URLEncoder.encode(userIdentifier, "UTF-8"));
                    if (tallyType != null) {
                        // for the purposes of this export, tallyType is fixed
                        voteObject.key("tallyType");
                        voteObject.value(tallyType);
                    }
                    voteObject.endObject();
                }
            }
        }
    }

    public static void extractTranslations(final JSONWriter writer, final Iterable<Resource> translations)
        throws JSONException, IOException {
        for (final Resource translation : translations) {
            final JSONArray timestampFields = new JSONArray();
            final ValueMap vm = translation.adaptTo(ValueMap.class);
            if (!vm.containsKey("jcr:description")) {
                continue; //if there's no translation, we're done here
            }
            String languageLabel = translation.getName();
            if (languageLabel.equals("nb")) {
                // SPECIAL CASE FOR LEGACY EXPORTER ONLY:
                // the label for norwegian changed between 6.0 and 6.1
                // (i.e. this section must be removed for 6.1 exporter)
                languageLabel = "no";
            }
            writer.key(languageLabel);

            JSONWriter translationObject = writer.object();
            translationObject.key("jcr:description");
            translationObject.value(URLEncoder.encode((String) vm.get("jcr:description"), "UTF-8"));
            if (vm.containsKey("jcr:createdBy")) {
                translationObject.key("jcr:createdBy");
                translationObject.value(URLEncoder.encode((String) vm.get("jcr:createdBy"), "UTF-8"));
            }
            if (vm.containsKey("jcr:title")) {
                translationObject.key("jcr:title");
                translationObject.value(URLEncoder.encode((String) vm.get("jcr:title"), "UTF-8"));
            }
            if (vm.containsKey("postEdited")) {
                translationObject.key("postEdited");
                translationObject.value(vm.get("postEdited"));
            }
            if (vm.containsKey("translationDate")) {
                translationObject.key("translationDate");
                translationObject.value(((Calendar) vm.get("translationDate")).getTimeInMillis());
                timestampFields.put("translationDate");
            }
            if (vm.containsKey("jcr:created")) {
                translationObject.key("jcr:created");
                translationObject.value(((Calendar) vm.get("jcr:created")).getTimeInMillis());
                timestampFields.put("jcr:created");
            }
            if (timestampFields.length() > 0) {
                translationObject.key(ContentTypeDefinitions.LABEL_TIMESTAMP_FIELDS);
                translationObject.value(timestampFields);
            }
            translationObject.endObject();
        }
    }

    public static void extractComment(final JSONWriter writer, final Comment post, final ResourceResolver resolver,
        final Writer responseWriter) throws JSONException, IOException {

        final ValueMap vm = post.getProperties();
        final JSONArray timestampFields = new JSONArray();
        for (final Map.Entry<String, Object> prop : vm.entrySet()) {
            final Object value = prop.getValue();
            if (value instanceof String[]) {
                final JSONArray list = new JSONArray();
                for (String v : (String[]) value) {
                    list.put(v);
                }
                writer.key(prop.getKey());
                writer.value(list);
            } else if (prop.getKey().equals("sentiment")) {
                    writer.key(prop.getKey());
                    //1 = 1, 2 = 3, 3 = 5, 4 = 8, 5 = 10
                    short shortValue = Short.parseShort(value.toString());
                    switch (shortValue) {
                        case 1:
                            writer.value(1);
                            break;
                        case 2:
                            writer.value(3);
                            break;
                        case 3:
                            writer.value(5);
                            break;
                        case 4:
                            writer.value(8);
                            break;
                        case 5:
                            writer.value(10);
                            break;
                        default:
                            writer.value(value);
                    }
            } else if (value instanceof GregorianCalendar) {
                timestampFields.put(prop.getKey());
                writer.key(prop.getKey());
                writer.value(((Calendar) value).getTimeInMillis());
            } else if (prop.getKey().equals("sling:resourceType")) {
                writer.key(prop.getKey());
                writer.value(Comment.RESOURCE_TYPE);
            } else {
                writer.key(prop.getKey());
                try {
                    writer.value(URLEncoder.encode(prop.getValue().toString(), "UTF-8"));
                } catch (final UnsupportedEncodingException e) {
                    throw new JSONException("Unsupported encoding (UTF-8) for resource at " + post.getPath(), e);
                }
            }
        }
        if (timestampFields.length() > 0) {
            writer.key(ContentTypeDefinitions.LABEL_TIMESTAMP_FIELDS);
            writer.value(timestampFields);
        }
        final Resource thisResource = resolver.getResource(post.getPath());
        final Resource attachments = thisResource.getChild("attachments");
        if (attachments != null) {
            writer.key(ContentTypeDefinitions.LABEL_ATTACHMENTS);
            final JSONWriter attachmentsWriter = writer.array();
            for (final Resource attachment : attachments.getChildren()) {
                UGCExportHelper.extractAttachment(responseWriter, attachmentsWriter.object(), attachment);
                attachmentsWriter.endObject();
            }
            writer.endArray();
        }
        final Iterable<Resource> children = thisResource.getChildren();
        for (final Resource child : children) {
            if (child.isResourceType("social/tally/components/hbs/voting") ||
                child.isResourceType("social/tally/components/voting")) {
                writer.key(ContentTypeDefinitions.LABEL_TALLY);
                final JSONWriter voteObjects = writer.array();
                UGCExportHelper.extractTally(voteObjects, child, "Voting");
                writer.endArray();
            } else if (child.getName().equals("translation")) {
                extractTranslation(writer, child);
            } 
        }
        final Iterator<Comment> posts = post.getComments();
        if (posts.hasNext()) {
            writer.key(ContentTypeDefinitions.LABEL_REPLIES);
            final JSONWriter replyWriter = writer.object();
            while (posts.hasNext()) {
                Comment childPost = posts.next();
                replyWriter.key(childPost.getId());
                extractComment(replyWriter.object(), childPost, resolver, responseWriter);
                replyWriter.endObject();
            }
            writer.endObject();
        }
    }

    public static void extractTopic(final JSONWriter writer, final Post post, final ResourceResolver resolver,
        final String resourceType, final String childResourceType, final Writer responseWriter) throws JSONException,
        IOException {

        final ValueMap vm = post.getProperties();
        final JSONArray timestampFields = new JSONArray();
        for (final Map.Entry<String, Object> prop : vm.entrySet()) {
            final Object value = prop.getValue();
            if (value instanceof String[]) {
                final JSONArray list = new JSONArray();
                for (String v : (String[]) value) {
                    list.put(v);
                }
                writer.key(prop.getKey());
                writer.value(list);
            } else if (value instanceof GregorianCalendar) {
                timestampFields.put(prop.getKey());
                writer.key(prop.getKey());
                writer.value(((Calendar) value).getTimeInMillis());
            } else if (prop.getKey().equals("sling:resourceType")) {
                writer.key(prop.getKey());
                writer.value(resourceType);
            } else if (prop.getKey().equals("sentiment")) {
                writer.key(prop.getKey());
                //1 = 1, 2 = 3, 3 = 5, 4 = 8, 5 = 10
                short shortValue = Short.parseShort(value.toString());
                switch (shortValue) {
                    case 1:
                        writer.value(1);
                        break;
                    case 2:
                        writer.value(3);
                        break;
                    case 3:
                        writer.value(5);
                        break;
                    case 4:
                        writer.value(8);
                        break;
                    case 5:
                        writer.value(10);
                        break;
                    default:
                        writer.value(value);
                }
            } else {
                writer.key(prop.getKey());
                try {
                    writer.value(URLEncoder.encode(prop.getValue().toString(), "UTF-8"));
                } catch (final UnsupportedEncodingException e) {
                    throw new JSONException("Unsupported encoding (UTF-8) for resource at " + post.getPath(), e);
                }
            }
        }
        if (timestampFields.length() > 0) {
            writer.key(ContentTypeDefinitions.LABEL_TIMESTAMP_FIELDS);
            writer.value(timestampFields);
        }
        final Resource thisResource = resolver.getResource(post.getPath());
        final Resource attachments = thisResource.getChild("attachments");
        if (attachments != null) {
            writer.key(ContentTypeDefinitions.LABEL_ATTACHMENTS);
            final JSONWriter attachmentsWriter = writer.array();
            for (final Resource attachment : attachments.getChildren()) {
                UGCExportHelper.extractAttachment(responseWriter, attachmentsWriter.object(), attachment);
                attachmentsWriter.endObject();
            }
            writer.endArray();
        }
        final Iterable<Resource> children = thisResource.getChildren();
        for (final Resource child : children) {
            if (child.isResourceType("social/tally/components/hbs/voting") ||
                child.isResourceType("social/tally/components/voting")) {
                writer.key(ContentTypeDefinitions.LABEL_TALLY);
                final JSONWriter voteObjects = writer.array();
                UGCExportHelper.extractTally(voteObjects, child, "Voting");
                writer.endArray();
            } else if (child.getName().equals("translation")) {
                extractTranslation(writer, child);
            }
        }
        final Iterator<Post> posts = post.getPosts();
        if (posts.hasNext()) {
            writer.key(ContentTypeDefinitions.LABEL_REPLIES);
            final JSONWriter replyWriter = writer.object();
            while (posts.hasNext()) {
                Post childPost = posts.next();
                replyWriter.key(childPost.getId());
                extractTopic(replyWriter.object(), childPost, resolver, childResourceType, childResourceType,
                    responseWriter);
                replyWriter.endObject();
            }
            writer.endObject();
        }
    }

    public static void extractJournalEntry(final JSONWriter entryObject, final JournalEntry entry,
        final Writer rawWriter) throws JSONException, IOException {
        final Resource thisResource = entry.getTextComment().getResource();
        final ValueMap vm = thisResource.adaptTo(ValueMap.class);
        final JSONArray timestampFields = new JSONArray();
        // make sure we only migrate the fields we want
        final Map<String, Boolean> fieldsToMigrate = new HashMap<String, Boolean>();
        fieldsToMigrate.put("userIdentifier", true);
        fieldsToMigrate.put("authorizableId", true);
        fieldsToMigrate.put("published", true);
        fieldsToMigrate.put("jcr:description", true);
        fieldsToMigrate.put("jcr:title", true);
        fieldsToMigrate.put("negative", true);
        fieldsToMigrate.put("positive", true);
        fieldsToMigrate.put("sentiment", true);
        for (final Map.Entry<String, Object> prop : vm.entrySet()) {
            if (!fieldsToMigrate.containsKey(prop.getKey())) {
                continue;
            }
            final Object value = prop.getValue();
            if (prop.getKey().equals("published") && value instanceof GregorianCalendar) {
                timestampFields.put("added");
                entryObject.key("added");
                entryObject.value(((Calendar) value).getTimeInMillis());
            } else {
                entryObject.key(prop.getKey());
                try {
                    entryObject.value(URLEncoder.encode(prop.getValue().toString(), "UTF-8"));
                } catch (final UnsupportedEncodingException e) {
                    throw new JSONException("Unsupported encoding (UTF-8) for resource at " + thisResource.getPath(), e);
                }
            }
        }
        // resource type has changed, so ignore the current one and force the new one
        entryObject.key("sling:resourceType");
        entryObject.value("social/journal/components/hbs/entry_topic");
        if (timestampFields.length() > 0) {
            entryObject.key(ContentTypeDefinitions.LABEL_TIMESTAMP_FIELDS);
            entryObject.value(timestampFields);
        }
        final Resource translationResource = thisResource.getChild("translation");
        if (null != translationResource) {
            extractTranslation(entryObject, translationResource);
        }
        if (entry.hasAttachments()) {
            entryObject.key(ContentTypeDefinitions.LABEL_ATTACHMENTS);
            JSONWriter attachmentsArray = entryObject.array();
            List<Resource> attachmentList = entry.getAttachments();
            for (final Resource attachment : attachmentList) {
                extractAttachment(rawWriter, attachmentsArray.object(), attachment);
                attachmentsArray.endObject();
            }
            entryObject.endArray();
        }
        if (entry.hasComments()) {
            final Iterator<Comment> posts = entry.getComments();
            entryObject.key(ContentTypeDefinitions.LABEL_REPLIES);
            final JSONWriter replyWriter = entryObject.object();
            while (posts.hasNext()) {
                final Comment childPost = posts.next();
                replyWriter.key(childPost.getId());
                extractComment(replyWriter.object(), childPost, entry.getResource().getResourceResolver(), rawWriter);
                replyWriter.endObject();
            }
            entryObject.endObject();
        }
    }

    public static void extractProperties(final JSONWriter object, final Map<String, Object> properties)
        throws JSONException {
        extractProperties(object, properties, null, null);
    }

    public static void extractTranslation(final JSONWriter writer, final Resource translationResource)
            throws JSONException, IOException {

        final Iterable<Resource> translations = translationResource.getChildren();
        final ValueMap props = translationResource.adaptTo(ValueMap.class);
        String languageLabel = (String) props.get("language");
        if (null == languageLabel) {
            languageLabel = (String) props.get("mtlanguage");
            if (null == languageLabel) {
                return;
            }
        }
        writer.key(ContentTypeDefinitions.LABEL_TRANSLATION);
        writer.object();
        writer.key("mtlanguage");
        if (languageLabel.equals("nb")) {
            // SPECIAL CASE FOR LEGACY EXPORTER ONLY:
            // the label for norwegian changed between 6.0 and 6.1
            // (i.e. this section must be removed for 6.1 exporter)
            languageLabel = "no";
        }

        writer.value(languageLabel);
        writer.key("jcr:created");
        writer.value(props.get("jcr:created", Long.class));
        writer.key("jcr:createdBy");
        writer.value(props.get("jcr:createdBy"));
        if (translations.iterator().hasNext()) {
            writer.key(ContentTypeDefinitions.LABEL_TRANSLATIONS);
            final JSONWriter translationObjects = writer.object();
            UGCExportHelper.extractTranslations(translationObjects, translations);
            writer.endObject();
        }
        writer.endObject();
    }

    public static void extractProperties(final JSONWriter object, final Map<String, Object> properties,
        final Map<String, String> renamedProperties, final String resourceType) throws JSONException {
        final JSONArray timestampFields = new JSONArray();
        boolean setResourceType = false;
        for (Map.Entry<String, Object> prop : properties.entrySet()) {
            Object value = prop.getValue();
            String key;
            if (null != renamedProperties && renamedProperties.containsKey(prop.getKey())) {
                key = renamedProperties.get(prop.getKey());
                if (null == key) {
                    continue; // we're excluding this property from the export
                }
            } else {
                key = prop.getKey();
            }
            if (null != resourceType && key.equals(JcrResourceConstants.SLING_RESOURCE_TYPE_PROPERTY)) {
                value = resourceType;
                setResourceType = true;
            }
            if (value instanceof String[]) {
                final JSONArray list = new JSONArray();
                for (String v : (String[]) value) {
                    try {
                        list.put(URLEncoder.encode(v, "UTF-8"));
                    } catch (final UnsupportedEncodingException e) {
                        throw new JSONException("String value cannot be encoded as UTF-8 for JSON transmission", e);
                    }
                }
                object.key(key);
                object.value(list);
            } else if (value instanceof GregorianCalendar) {
                timestampFields.put(key);
                object.key(key);
                object.value(((Calendar) value).getTimeInMillis());
            } else if (value instanceof InputStream) {
                object.key(ContentTypeDefinitions.LABEL_ENCODED_DATA_FIELDNAME);
                object.value(key);
                object.key(ContentTypeDefinitions.LABEL_ENCODED_DATA);
                try {
                    final InputStream data = (InputStream) value;
                    byte[] byteData = new byte[DATA_ENCODING_CHUNK_SIZE];
                    int read = 0;
                    final StringBuilder stringBuilder = new StringBuilder();
                    while (read != -1) {
                        read = data.read(byteData);
                        if (read > 0 && read < DATA_ENCODING_CHUNK_SIZE) {
                            // make a right-size container for the byte data actually read
                            byte[] byteArray = new byte[read];
                            System.arraycopy(byteData, 0, byteArray, 0, read);
                            byte[] encodedBytes = Base64.encodeBase64(byteArray);
                            stringBuilder.append(new String(encodedBytes));
                        } else if (read == DATA_ENCODING_CHUNK_SIZE) {
                            byte[] encodedBytes = Base64.encodeBase64(byteData);
                            stringBuilder.append(new String(encodedBytes));
                        }
                    }
                    object.value(stringBuilder.toString());
                } catch (IOException e) {
                    object.value(""); // if we error out on the first read attempt, we need a placeholder value still
                    object.key(ContentTypeDefinitions.LABEL_ERROR);
                    object.value("IOException while getting attachment: " + e.getMessage());
                }
            } else {
                object.key(key);
                try {
                    object.value(URLEncoder.encode(value.toString(), "UTF-8"));
                } catch (final UnsupportedEncodingException e) {
                    throw new JSONException("String value cannot be encoded as UTF-8 for JSON transmission", e);
                }
            }
        }
        if (null != resourceType && !setResourceType) { // make sure this gets included if it's been specified
            object.key(JcrResourceConstants.SLING_RESOURCE_TYPE_PROPERTY);
            object.value(resourceType);
        }
        if (timestampFields.length() > 0) {
            object.key(ContentTypeDefinitions.LABEL_TIMESTAMP_FIELDS);
            object.value(timestampFields);
        }
    }
}
