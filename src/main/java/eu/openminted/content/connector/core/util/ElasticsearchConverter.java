package eu.openminted.content.connector.core.util;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import eu.openminted.content.connector.Query;
import eu.openminted.content.connector.core.mappings.OMTDtoESMapper;
import eu.openminted.content.connector.faceting.OMTDFacetEnum;
import eu.openminted.content.connector.faceting.OMTDFacetInitializer;
import eu.openminted.registry.core.domain.Facet;
import eu.openminted.registry.core.domain.Value;
import eu.openminted.registry.domain.PublicationTypeEnum;
import io.searchbox.core.SearchResult;
import io.searchbox.core.SearchResult.Hit;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.apache.lucene.queryparser.flexible.standard.QueryParserUtil;
import uk.ac.core.elasticsearch.entities.ElasticSearchArticleMetadata;

/**
 * @author lucasanastasiou
 */
public class ElasticsearchConverter {

    public static List<String> DEFAULT_FACETS = Arrays.asList(new String[]{"authors", "journals", "licence", "publicationYear", "documentLanguage", "publicationType"});

    public static String constructElasticsearchScanAndScrollQueryFromOmtdQuery(Query query) {
        String keyword = query.getKeyword();
        if (keyword == null) {
            keyword = "";
        }

        String escapedKeyword = org.apache.lucene.queryparser.flexible.standard.QueryParserUtil.escape(keyword);

        String esQuery = "{\n"
                + "        \"query_string\": {\n"
                + "           \"query\": \"" + keyword + "\"\n"
                + "        }\n"
                + "    }";
        return keyword;
    }

    public static String constructElasticsearchQueryFromOmtdQuery(Query query) {
        int from = query.getFrom();
        int to = query.getTo();
        String keyword = query.getKeyword();
        String queryComponent = "";

        //
        // Query - either a keyword query or match all
        //
        queryComponent += "{\n"
                + "    \"query\":{\n"
                + "        \"filtered\":{\n"
                + "            \"query\": {\n";
        if (keyword == null || keyword.isEmpty() || keyword.equals("*")) {
            String matchAllQueryComponent = "\"match_all\": {}";
            queryComponent += matchAllQueryComponent;
        } else {
            String escapedKeyword = QueryParserUtil.escape(keyword);
            String keywordQueryComponent = "\"bool\": {\n"
                    + "                    \"must\": [\n"
                    + "                        {\n"
                    + "                       \"query_string\": {\n"
                    + "                             \"query\": \"" + escapedKeyword + "\"\n"
                    + "                         }\n"
                    + "                    }\n"
                    + "                    ]   \n"
                    + "                }\n";
            queryComponent += keywordQueryComponent;
        }
        queryComponent += "},";

        //
        // Parameters
        //
        Map<String, List<String>> params = query.getParams();

        // parameters in ES query are part of a boolean filter in a filtered query
        String paramsString = "";
        if (params != null && params.size() > 0) {
            for (String key : params.keySet()) {
                String esParameterName = OMTDtoESMapper.OMTD_TO_ES_PARAMETER_NAMES.get(key);

                if (esParameterName == null || esParameterName.isEmpty()) {
                    // a non-existent parameter in the omtd<->ES map was given, skip
                    continue;
                }

                // each of the values of this parameter becomes a should clause (equivalent of OR in ES lingo)
                if (params.get(key).size() > 0) {
                    paramsString += "{\n"
                            + "\"bool\": {\n"
                            + "     \"should\": [\n";
                    for (String value : params.get(key)) {

                        // convert language values to lowercase
                        if (key == OMTDFacetEnum.DOCUMENT_LANG.value()) {
                            value = value.toLowerCase();
                        }

                        paramsString += "{\"term\": { \"" + esParameterName + "\": \"" + value + "\" }},\n";
                    }
                    //remove trailing comma
                    paramsString = paramsString.replaceAll(",\n$", "");
                    paramsString += "]\n"
                            + "}\n"
                            + "},\n";
                }

            }
            //remove trailing comma
            paramsString = paramsString.replaceAll(",\n$", "");

        }

        //
        // Filter
        //
        // apart of default filters (only fulltext items and not deleted) add
        // the parameters as a filter
        //
        String filterQueryComponent = "\"filter\":{\n"
                + "            \"bool\":{\n"
                + "                \"must\":[\n"
                + "                    { \"exists\" : { \"field\" : \"fullText\" } },\n"
                + "                    { \"term\": { \"deleted\":\"ALLOWED\" } }\n";
        if (!paramsString.isEmpty()) {
            filterQueryComponent += "," + paramsString;
        }
        filterQueryComponent += "]}";
        filterQueryComponent += "                }\n"
                + "            }\n"
                + "     },";

        //        
        // Facets
        //
        List<String> facets = query.getFacets();

        if (facets
                == null || facets.isEmpty()) {
//            query.setFacets(DEFAULT_FACETS);
        }

        String facetString = "";
        facetString += " \"facets\" : {";
        // for each facet creates a line like:
        // \"yearFacet\" : { \"terms\" : {\"field\":\"year\"}}
        for (String facet : facets) {
            //special case for some fields (from the multi-field choose the non-analyzed version)
            String facetField = facet;
            String knownFacet = OMTDtoESMapper.OMTD_TO_ES_FACETS_NAMES.get(facet);
            if (knownFacet != null && !knownFacet.isEmpty()) {
                facetField = knownFacet;
            }
            facetString += "\"" + facet + "Facet\" : { \"terms\" : {\"field\" : \"" + facetField + "\"} },";
        }
        //remove trailing comma
        facetString = facetString.replaceAll(",$", "");
        facetString += "},";

        //-------------------------------------------------------
        //
        // Combine everything into a filtered query with facets
        //
        //-------------------------------------------------------
        String esQuery = queryComponent
                + filterQueryComponent
                + facetString
                + "    \"_source\": {\n"
                + "        \"exclude\": [ \"fullText\" ]\n"
                + "    },"
                + "    \"from\":" + from + ",\n"
                + "    \"size\":" + (to - from) + "\n"
                + "}";

        return esQuery;
    }

    public static List<Facet> getOmtdFacetsFromSearchResult(SearchResult searchResult, List<String> queryFacets) {
        List<Facet> omtdFacets = new ArrayList<>();

        try {
            JsonObject jsonObject = searchResult.getJsonObject();
            JsonObject facetsJsonObject = jsonObject.getAsJsonObject("facets");

            for (String f : queryFacets) {
                Facet omtdFacet = new Facet();
                omtdFacet.setLabel(f);
                omtdFacet.setField(f);

                JsonObject fJObj = facetsJsonObject.getAsJsonObject(f + "Facet");
                JsonArray terms = fJObj.getAsJsonArray("terms");

                List<Value> omtdFacetValues = new ArrayList<>();
                for (int i = 0; i < terms.size(); i++) {
                    JsonObject fTermElement = terms.get(i).getAsJsonObject();
                    String term = fTermElement.get("term").getAsString();
                    int count = fTermElement.get("count").getAsInt();
                    Value omtdValue = new Value();
                    omtdValue.setValue(term);
                    omtdValue.setCount(count);
                    omtdFacetValues.add(omtdValue);
                }

                omtdFacet.setValues(omtdFacetValues);
                omtdFacets.add(omtdFacet);
            }

            // manually setting all documents as fulltext
            Facet documentTypeFacet = new Facet();
            List<Value> omtdFacetValues = new ArrayList<>();
            OMTDFacetInitializer omtdFacetInitializer = new OMTDFacetInitializer();

            documentTypeFacet.setField(OMTDFacetEnum.DOCUMENT_TYPE.value());
            documentTypeFacet.setLabel(omtdFacetInitializer.getOmtdFacetLabels().get(OMTDFacetEnum.DOCUMENT_TYPE));

            String term = "Fulltext";
            int count = searchResult.getTotal();
            Value omtdValue = new Value();
            omtdValue.setValue(term);
            omtdValue.setCount(count);
            omtdFacetValues.add(omtdValue);
            documentTypeFacet.setValues(omtdFacetValues);
            omtdFacets.add(documentTypeFacet);

            setRightsFacetValue(omtdFacets, count);

            setDocumentFacetValue(omtdFacets);

            setLanguageFacetValue(omtdFacets, count);

        } catch (Exception e) {
            e.printStackTrace();
        }
        return omtdFacets;
    }

    private static void setRightsFacetValue(List<Facet> omtdFacets, int count) {
        // manually setting all documents rights as open access
        for (Facet f : omtdFacets) {
            if (f.getField().equalsIgnoreCase("RIGHTS")) {
                List<Value> rightsFacetValues = new ArrayList<>();
                Value rightsValue = new Value();
                rightsValue.setValue("Open Access");
                rightsValue.setCount(count);
                rightsFacetValues.add(rightsValue);
                f.setValues(rightsFacetValues);
            }

        }
    }

    public static List<String> getPublicationsFromSearchResultAsString(SearchResult searchResult) {
        List<String> publications = new ArrayList<>();

        try {
            List<Hit<ElasticSearchArticleMetadata, Void>> hits = searchResult.getHits(ElasticSearchArticleMetadata.class
            );
            List<Hit<Map, Void>> mapHits = searchResult.getHits(Map.class
            );
            for (io.searchbox.core.SearchResult.Hit<ElasticSearchArticleMetadata, Void> hit : hits) {
                if (hit != null && hit.source != null) {
                    publications.add(hit.source.toString());
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return publications;
    }

    public static List<ElasticSearchArticleMetadata> getPublicationsFromSearchResult(SearchResult searchResult) {
        List<ElasticSearchArticleMetadata> publications = new ArrayList<>();

        try {
            List<io.searchbox.core.SearchResult.Hit<ElasticSearchArticleMetadata, Void>> hits = searchResult.getHits(ElasticSearchArticleMetadata.class
            );
            for (io.searchbox.core.SearchResult.Hit<ElasticSearchArticleMetadata, Void> hit : hits) {
                publications.add(hit.source);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return publications;
    }

    public static List<ElasticSearchArticleMetadata> getPublicationsFromResultJsonArray(JsonArray hits) {
        java.util.Random random = new Random();
        List<ElasticSearchArticleMetadata> results = new ArrayList<>();
        try {
            for (int i = 0; i < hits.size(); i++) {
                JsonElement obj = hits.get(i).getAsJsonObject().get("_source");
                Gson gson = new GsonBuilder().setDateFormat("yyyy-MM-dd").create();

                try {
                    ElasticSearchArticleMetadata esam = gson.fromJson(obj, ElasticSearchArticleMetadata.class
                    );
                    results.add(esam);
                } catch (JsonSyntaxException j) {
                    System.out.println("json syntax exception " + j.getMessage());
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return results;
    }

    public static String constructFetchByIdentifierElasticsearchQuery(String identifier) {
        //check if identifier is a number
        Integer id = null;
        try {
            id = Integer.parseInt(identifier);
        } catch (NumberFormatException nfe) {
            //not a number
        }
        String esQuery = "";
        if (id != null) {

            esQuery = "{\n"
                    + "    \"query\": {\n"
                    + "        \"bool\":{\n"
                    + "            \"should\": [\n"
                    + "               {\"term\": {\"identifiers\": {\"value\":\"" + identifier + "\" }}},\n"
                    + "               {\"term\": {\"id\": {\"value\":\"" + id.toString() + "\" }}}\n"
                    + "            ]\n"
                    + "        }\n"
                    + "    }\n"
                    + "}";

        } else {
            esQuery = "{\n"
                    + "    \"query\": {\n"
                    + "        \"bool\":{\n"
                    + "            \"should\": [\n"
                    + "               {\"term\": {\"identifiers\": {\"value\":\"" + identifier + "\" }}}\n"
                    + "            ]\n"
                    + "        }\n"
                    + "    }\n"
                    + "}";
        }
        return esQuery;
    }

    public static void main(String args[]) {
        Query omtdQuery = new Query();
        omtdQuery.setFrom(1);
        omtdQuery.setTo(15);
        omtdQuery.setKeyword("Deep learning");
//        omtdQuery.setKeyword("*");
        Map<String, List<String>> omtdParameters = new HashMap<>();
        List<String> yearsParameter = new ArrayList<>();
        yearsParameter.add("2012");
        yearsParameter.add("2013");
        omtdParameters.put("publicationYear", yearsParameter);
        List<String> languages = new ArrayList<>();
        languages.add("english");
        languages.add("german");
        omtdParameters.put("documentLanguage", languages);

        omtdQuery.setParams(null);
        omtdQuery.setFacets(DEFAULT_FACETS);

        String esQuery = ElasticsearchConverter.constructElasticsearchQueryFromOmtdQuery(omtdQuery);
        System.out.println("esQuery = " + esQuery);

        System.out.println("omt = " + omtdQuery.toString());
        Gson gson = new Gson();
        System.out.println(gson.toJson(omtdQuery));

    }

    private static void setDocumentFacetValue(List<Facet> omtdFacets) {
        // manually mapping CORE document types to OMTD document types        
        for (Facet f : omtdFacets) {
            if (f.getField().equalsIgnoreCase("publicationtype")) {

                List<Value> pubTypeValues = f.getValues();

                int researchArticleCount = 0;
                int thesisArticleCount = 0;
                int otherCount = 0;

                for (Value ptValue : pubTypeValues) {
                    if (ptValue.getValue().equalsIgnoreCase("Research")) {
                        researchArticleCount += ptValue.getCount();
                    } else if (ptValue.getValue().equalsIgnoreCase("Thesis")) {
                        thesisArticleCount += ptValue.getCount();
                    } else {
                        otherCount += ptValue.getCount();
                    }
                }

                Value resArticleValue = new Value();
//                resArticleValue.setValue(PublicationTypeEnum.RESEARCH_ARTICLE.value());
                resArticleValue.setValue("Research Article");
                resArticleValue.setCount(researchArticleCount);

                Value thesisValue = new Value();
//                thesisValue.setValue(PublicationTypeEnum.THESIS.value());
                thesisValue.setValue("Thesis");
                thesisValue.setCount(thesisArticleCount);

                Value otherValue = new Value();
//                otherValue.setValue(PublicationTypeEnum.OTHER.value());
                otherValue.setValue("Other");
                otherValue.setCount(otherCount);

                List<Value> newPubTypeValues = new ArrayList<>();
                newPubTypeValues.add(resArticleValue);
                newPubTypeValues.add(thesisValue);
                newPubTypeValues.add(otherValue);
                f.setValues(newPubTypeValues);
            }
        }

    }

    private static void setLanguageFacetValue(List<Facet> omtdFacets, int count) {
        // manually setting undetermined language as :
        // undetermined = total - sum(known_languages)
        for (Facet f : omtdFacets) {
            if (f.getField().equalsIgnoreCase("documentlanguage")) {
                List<Value> langFacetValues = f.getValues();

                int langCount = 0;
                for (Value langValue : langFacetValues) {
                    langCount += langValue.getCount();
                }

                Value langValue = new Value();
                langValue.setValue("Undetermined");
                langValue.setCount(count - langCount);
                langFacetValues.add(langValue);
                f.setValues(langFacetValues);
            }

        }
    }

}
