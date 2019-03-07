//******************************************************************************
//                                       ProvenanceDAOMongo.java
// SILEX-PHIS
// Copyright © INRA 2019
// Creation date: 5 mars 2019
// Contact: morgane.vidal@inra.fr, anne.tireau@inra.fr, pascal.neveu@inra.fr
//******************************************************************************
package phis2ws.service.dao.mongo;

import com.mongodb.BasicDBObject;
import com.mongodb.MongoClient;
import com.mongodb.MongoException;
import com.mongodb.client.ClientSession;
import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoCursor;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import javax.ws.rs.core.Response;
import org.bson.BSONObject;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import phis2ws.service.PropertiesFileManager;
import phis2ws.service.dao.manager.DAOMongo;
import phis2ws.service.documentation.StatusCodeMsg;
import phis2ws.service.ontologies.Oeso;
import phis2ws.service.utils.POSTResultsReturn;
import phis2ws.service.utils.UriGenerator;
import phis2ws.service.view.brapi.Status;
import phis2ws.service.view.model.phis.provenance.Provenance;

/**
 * Allows CRUD methods of provenances in mongo.
 * @author Morgane Vidal <morgane.vidal@inra.fr>
 */
public class ProvenanceDAOMongo extends DAOMongo<Provenance> {
    
    private final static Logger LOGGER = LoggerFactory.getLogger(ProvenanceDAOMongo.class);
    
    //Mongodb collection of the provenance
    private final String provenanceCollectionName = PropertiesFileManager.getConfigFileProperty("mongodb_nosql_config", "provenance");
        
    //MongoFields labels, used to query (CRUD) the provenance mongo data
    private final static String DB_FIELD_URI = "uri";
    private final static String DB_FIELD_LABEL = "label";
    private final static String DB_FIELD_COMMENT = "comment";
    private final static String DB_FIELD_METADATA = "metadata";

    @Override
    protected BasicDBObject prepareSearchQuery() {
        throw new UnsupportedOperationException("Not supported yet."); //To change body of generated methods, choose Tools | Templates.
    }

    @Override
    public ArrayList<Provenance> allPaginate() {
        throw new UnsupportedOperationException("Not supported yet."); //To change body of generated methods, choose Tools | Templates.
    }
    
    /**
     * Generates the query to search provenances by uri, label, comment and additional json filters
     * @param searchProvenance
     * @param jsonValueFilter
     * @example
     * { 
     *      "metadata.SensingDevice" : "http://www.opensilex.org/demo/s001", 
     *      "metadata.Vector" : "http://www.opensilex.org/demo/v001", 
     *      "uri" : "http://www.opensilex.org/opensilex/id/provenance/1551805521606", 
     *      "label" : { "$regex" : "PROV2019-LEAF", "$options" : "" }, 
     *      "comment" : { "$regex" : "plant", "$options" : "" } 
     * }
     * @return the query
     */
    protected BasicDBObject searchQuery(Provenance searchProvenance, String jsonValueFilter) {
        BasicDBObject query = new BasicDBObject();
        
        if (jsonValueFilter != null) {
            query.putAll((BSONObject) BasicDBObject.parse(jsonValueFilter));
        }
        
        if (searchProvenance.getUri() != null) {
            query.append(DB_FIELD_URI, searchProvenance.getUri());
        }
        
        if (searchProvenance.getLabel() != null) {
            query.append(DB_FIELD_LABEL, java.util.regex.Pattern.compile(searchProvenance.getLabel()));
        }
        
        if (searchProvenance.getComment() != null) {
            query.append(DB_FIELD_COMMENT, java.util.regex.Pattern.compile(searchProvenance.getComment()));
        }
        LOGGER.debug(query.toJson());
        
        return query;
    }
    
    /**
     * Count the number of results for the query
     * @param searchProvenance
     * @param jsonValueFilter
     * @return the number of provenances corresponding to the search params
     */
    public int count(Provenance searchProvenance, String jsonValueFilter) {
        MongoCollection<Document> provenanceCollection = database.getCollection(provenanceCollectionName);

        // Get the filter query
        BasicDBObject query = searchQuery(searchProvenance, jsonValueFilter);
        
        // Return the document count
        return (int)provenanceCollection.countDocuments(query);
    }
    
    /**
     * Generates the document to insert provenance.
     * @example
     * { 
     *      "uri" : "http://www.opensilex.org/opensilex/id/provenance/1551877498746",
     *      "label" : "PROV2019-LEAF",
     *      "comment" : "In this provenance we have count the number of leaf per plant",
     *      "metadata" : { 
     *          "SensingDevice" : "http://www.opensilex.org/demo/s001",
     *          "Vector" : "http://www.opensilex.org/demo/v001" 
     *      }
     * }
     * @param provenance
     * @return the document to insert
     */
    private Document prepareInsertProvenanceDocument(Provenance provenance) {
        Document provenanceDocument = new Document();
        
        provenanceDocument.append(DB_FIELD_URI, provenance.getUri());
        provenanceDocument.append(DB_FIELD_LABEL, provenance.getLabel());
        provenanceDocument.append(DB_FIELD_COMMENT, provenance.getComment());
        provenanceDocument.append(DB_FIELD_METADATA, provenance.getMetadata());
        
        LOGGER.debug(provenanceDocument.toJson());
        
        return provenanceDocument;
    }
    
    /**
     * Insert the given provenances in the mongodb database
     * @param provenances
     * @return the insertion result
     */
    private POSTResultsReturn insert(List<Provenance> provenances) throws Exception {
        // Initialize transaction
        MongoClient client = DAOMongo.getMongoClient();
        ClientSession session = client.startSession();
        session.startTransaction();
        
        POSTResultsReturn result = null;
        List<Status> status = new ArrayList<>();
        List<String> createdResources = new ArrayList<>();
        
        //1. Prepare all the documents to insert
        List<Document> provenancesDocuments = new ArrayList<>();
        UriGenerator uriGenerator = new UriGenerator();
        for (Provenance provenance : provenances) {
            //1. Generates the provenance uri
            provenance.setUri(uriGenerator.generateNewInstanceUri(Oeso.CONCEPT_PROVENANCE.toString(), null, null));
            
            //2. Generates the document insert
            provenancesDocuments.add(prepareInsertProvenanceDocument(provenance));
        }
        
        
        // Use of AtomicBoolean to use it inside the lambda loop (impossible with a standart boolean)
        // @see: https://stackoverflow.com/questions/46713854/which-is-the-best-way-to-set-drop-boolean-flag-inside-lambda-function
        AtomicBoolean hasError = new AtomicBoolean(false);
        MongoCollection<Document> provenanceCollection = database.getCollection(provenanceCollectionName);
        
        //2. Create index on the provenance uri
        Bson indexFields = Indexes.ascending(
            DB_FIELD_URI
        );
        IndexOptions indexOptions = new IndexOptions().unique(true);
        provenanceCollection.createIndex(indexFields, indexOptions);

        //3. Insert all the provenances
        try {
            provenanceCollection.insertMany(session, provenancesDocuments);
            status.add(new Status(
                StatusCodeMsg.RESOURCES_CREATED, 
                StatusCodeMsg.INFO, 
                StatusCodeMsg.DATA_INSERTED
            ));
            
            for (Provenance provenance : provenances) {
                createdResources.add(provenance.getUri());
            }

        } catch (MongoException ex) {
            // Define that an error occurs
            hasError.set(true);
            // Add the original exception message for debugging
            status.add(new Status(
                StatusCodeMsg.UNEXPECTED_ERROR, 
                StatusCodeMsg.ERR, 
                StatusCodeMsg.DATA_REJECTED + " - " + ex.getMessage()
            ));
        }
        
        //4. Prepare result to return
        result = new POSTResultsReturn(hasError.get());
        result.statusList = status;
        
        if (!hasError.get()) {
            // If no errors commit transaction
            session.commitTransaction();
            result.setHttpStatus(Response.Status.CREATED);
            result.createdResources = createdResources;
        } else {
            // If errors abort transaction
            session.abortTransaction();
            result.setHttpStatus(Response.Status.BAD_REQUEST);
        }
        
        // Close transaction session
        session.close();
        return result;
    }
    
    /**
     * Insert the given provenances. No check is needed, the Java Beans validation is enought. 
     * @param provenances
     * @return the insertion result.
     */
    public POSTResultsReturn checkAndInsert(List<Provenance> provenances) {
        try {
            return insert(provenances);
        } catch (Exception ex) {
            return new POSTResultsReturn(false, Response.Status.INTERNAL_SERVER_ERROR, ex.toString());
        }
    }
    
    /**
     * 
     * @param searchProvenance
     * @param jsonValueFilter
     * @return the list of the provenances corresponding to the given search params
     */
    public ArrayList<Provenance> getProvenances(Provenance searchProvenance, String jsonValueFilter) {
        MongoCollection<Document> provenanceCollection = database.getCollection(provenanceCollectionName);
        // Get the filter query
        BasicDBObject query = searchQuery(searchProvenance, jsonValueFilter);
        
        // Get paginated documents
        FindIterable<Document> provenancesMongo = provenanceCollection.find(query);
        
        // Define pagination for the request
        provenancesMongo = provenancesMongo.skip(page * pageSize).limit(pageSize);

        ArrayList<Provenance> provenances = new ArrayList<>();
        
        // For each document, create a Provenance instance and add it to the result list
        try (MongoCursor<Document> provenancesCursor = provenancesMongo.iterator()) {
            while (provenancesCursor.hasNext()) {
                Document provenanceDocument = provenancesCursor.next();
                
                // Create and define the Provenance
                Provenance provenance = new Provenance();
                provenance.setUri(provenanceDocument.getString(DB_FIELD_URI));
                provenance.setLabel(provenanceDocument.getString(DB_FIELD_LABEL));
                provenance.setComment(provenanceDocument.getString(DB_FIELD_COMMENT));
                provenance.setMetadata(provenanceDocument.get(DB_FIELD_METADATA));
                
                // Add the provenance to the list
                provenances.add(provenance);
            }
        }
        
        return provenances;
    }
}