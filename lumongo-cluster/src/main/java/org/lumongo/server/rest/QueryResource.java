package org.lumongo.server.rest;

import com.cedarsoftware.util.io.JsonWriter;
import com.google.protobuf.ByteString;
import com.google.protobuf.util.JsonFormat;
import com.mongodb.BasicDBObject;
import org.apache.log4j.Logger;
import org.bson.BSON;
import org.lumongo.LumongoConstants;
import org.lumongo.cluster.message.Lumongo;
import org.lumongo.cluster.message.Lumongo.CountRequest;
import org.lumongo.cluster.message.Lumongo.FacetRequest;
import org.lumongo.cluster.message.Lumongo.LMFacet;
import org.lumongo.cluster.message.Lumongo.QueryRequest;
import org.lumongo.cluster.message.Lumongo.QueryResponse;
import org.lumongo.server.index.LumongoIndexManager;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.MediaType;
import java.util.List;

@Path(LumongoConstants.QUERY_URL)
public class QueryResource {

	private final static Logger log = Logger.getLogger(QueryResource.class);

	private LumongoIndexManager indexManager;

	public QueryResource(LumongoIndexManager indexManager) {
		this.indexManager = indexManager;
	}

	@GET
	@Produces({ MediaType.APPLICATION_JSON + ";charset=utf-8" })
	public String get(@QueryParam(LumongoConstants.INDEX) List<String> indexName, @QueryParam(LumongoConstants.QUERY) String query,
			@QueryParam(LumongoConstants.QUERY_FIELD) List<String> queryFields, @QueryParam(LumongoConstants.FILTER_QUERY) List<String> filterQueries,
			@QueryParam(LumongoConstants.FIELDS) List<String> fields, @QueryParam(LumongoConstants.FETCH) Boolean fetch,
			@QueryParam(LumongoConstants.ROWS) int rows, @QueryParam(LumongoConstants.FACET) List<String> facet,
			@QueryParam(LumongoConstants.SORT) List<String> sort, @QueryParam(LumongoConstants.PRETTY) boolean pretty,
			@QueryParam(LumongoConstants.FORMAT) String format) {

		QueryRequest.Builder qrBuilder = QueryRequest.newBuilder().addAllIndex(indexName);
		if (query != null) {
			qrBuilder.setQuery(query);
		}
		qrBuilder.setAmount(rows);

		if (queryFields != null) {
			for (String queryField : queryFields) {
				qrBuilder.addQueryField(queryField);
			}
		}

		if (filterQueries != null) {
			for (String filterQuery : filterQueries) {
				qrBuilder.addFilterQuery(filterQuery);
			}
		}

		if (fields != null) {
			for (String field : fields) {
				if (field.startsWith("-")) {
					qrBuilder.addDocumentMaskedFields(field.substring(1, field.length()));
				}
				else {
					qrBuilder.addDocumentFields(field);
				}
			}
		}

		qrBuilder.setResultFetchType(Lumongo.FetchType.FULL);
		if (fetch != null && !fetch) {
			qrBuilder.setResultFetchType(Lumongo.FetchType.NONE);
		}

		FacetRequest.Builder frBuilder = FacetRequest.newBuilder();
		for (String f : facet) {
			Integer count = null;
			if (f.contains(":")) {
				String countString = f.substring(f.indexOf(":") + 1);
				f = f.substring(0, f.indexOf(":"));
				try {
					count = Integer.parseInt(countString);
				}
				catch (Exception e) {
					return "Invalid facet count <" + countString + "> for facet <" + f + ">";
				}
			}

			CountRequest.Builder countBuilder = CountRequest.newBuilder();
			LMFacet lmFacet = LMFacet.newBuilder().setLabel(f).build();
			CountRequest.Builder facetBuilder = countBuilder.setFacetField(lmFacet);
			if (count != null) {
				facetBuilder.setMaxFacets(count);
			}
			frBuilder.addCountRequest(facetBuilder);
		}
		qrBuilder.setFacetRequest(frBuilder);

		Lumongo.SortRequest.Builder sortRequest = Lumongo.SortRequest.newBuilder();
		for (String sortField : sort) {

			Lumongo.FieldSort.Builder fieldSort = Lumongo.FieldSort.newBuilder();
			if (sortField.contains(":")) {
				String sortDir = sortField.substring(sortField.indexOf(":") + 1);
				sortField = sortField.substring(0, sortField.indexOf(":"));


				if ("-1".equals(sortDir) || "DESC".equalsIgnoreCase(sortDir)) {
					fieldSort.setDirection(Lumongo.FieldSort.Direction.DESCENDING);
				}
				else if ("1".equals(sortDir) || "ASC".equalsIgnoreCase(sortDir)) {
					fieldSort.setDirection(Lumongo.FieldSort.Direction.ASCENDING);
				}
				else {
					return "Invalid sort direction <" + sortDir + "> for field <" + sortField + ">.  Expecting -1/1 or DESC/ASC";
				}
			}
			fieldSort.setSortField(sortField);
			sortRequest.addFieldSort(fieldSort);
		}
		qrBuilder.setSortRequest(sortRequest);

		try {
			QueryResponse qr = indexManager.query(qrBuilder.build());

			String response;
			if ("proto".equalsIgnoreCase(format)) {
				response = JsonFormat.printer().print(qr);
			}
			else {
				response = getStandardResponse(qr);
			}

			if (pretty) {
				response = JsonWriter.formatJson(response);
			}
			return response;
		}
		catch (Exception e) {
			log.error(e.getClass().getSimpleName() + ":", e);
			return e.getMessage();
		}

	}

	private String getStandardResponse(QueryResponse qr) {
		StringBuilder responseBuilder = new StringBuilder();
		responseBuilder.append("{");
		responseBuilder.append("\"totalHits\": ");
		responseBuilder.append(qr.getTotalHits());

		if (!qr.getResultsList().isEmpty()) {
			responseBuilder.append(",");
			responseBuilder.append("\"results\": [");
			boolean first = true;
			for (Lumongo.ScoredResult sr : qr.getResultsList()) {
				if (first) {
					first = false;
				}
				else {
					responseBuilder.append(",");
				}
				responseBuilder.append("{");
				responseBuilder.append("\"id\": ");
				responseBuilder.append(sr.getUniqueId());
				responseBuilder.append(",");
				responseBuilder.append("\"score\": ");
				responseBuilder.append(sr.getScore());
				responseBuilder.append(",");
				responseBuilder.append("\"indexName\": ");
				responseBuilder.append("\"").append(sr.getIndexName()).append("\"");

				if (sr.hasResultDocument()) {
					responseBuilder.append(",");
					Lumongo.ResultDocument document = sr.getResultDocument();
					ByteString bs = document.getDocument();
					BasicDBObject dbObject = new BasicDBObject();
					dbObject.putAll(BSON.decode(bs.toByteArray()));
					responseBuilder.append("\"document\": ");
					responseBuilder.append(dbObject.toString());

				}

				responseBuilder.append("}");
			}
			responseBuilder.append("]");
		}

		if (!qr.getFacetGroupList().isEmpty()) {
			responseBuilder.append(",");
			responseBuilder.append("\"facets\": [");
			boolean first = true;
			for (Lumongo.FacetGroup facetGroup : qr.getFacetGroupList()) {
				if (first) {
					first = false;
				}
				else {
					responseBuilder.append(",");
				}
				responseBuilder.append("{");
				responseBuilder.append("\"field\": \"");
				responseBuilder.append(facetGroup.getCountRequest().getFacetField().getLabel());
				responseBuilder.append("\",");
				responseBuilder.append("\"values\": [");

				boolean firstInner = true;
				for (Lumongo.FacetCount facetCount : facetGroup.getFacetCountList()) {
					if (firstInner) {
						firstInner = false;
					}
					else {
						responseBuilder.append(",");
					}

					responseBuilder.append("{");
					responseBuilder.append("\"label\": \"");
					responseBuilder.append(facetCount.getFacet());
					responseBuilder.append("\",");
					responseBuilder.append("\"count\": ");
					responseBuilder.append(facetCount.getCount());
					responseBuilder.append("}");
				}
				responseBuilder.append("]");

				responseBuilder.append("}");
			}
			responseBuilder.append("]");
		}

		responseBuilder.append("}");

		return responseBuilder.toString();
	}
}
