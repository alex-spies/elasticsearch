/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.esql.action;

import org.elasticsearch.action.ActionListener;
import org.elasticsearch.client.internal.node.NodeClient;
import org.elasticsearch.common.UUIDs;
import org.elasticsearch.core.RestApiVersion;
import org.elasticsearch.logging.LogManager;
import org.elasticsearch.logging.Logger;
import org.elasticsearch.rest.BaseRestHandler;
import org.elasticsearch.rest.RestRequest;
import org.elasticsearch.rest.action.RestCancellableNodeClient;
import org.elasticsearch.xcontent.XContentParser;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import static org.elasticsearch.rest.RestRequest.Method.POST;
import static org.elasticsearch.xpack.esql.formatter.TextFormat.URL_PARAM_DELIMITER;

public class RestEsqlQueryAction extends BaseRestHandler {
    private static final Logger LOGGER = LogManager.getLogger(RestEsqlQueryAction.class);

    @Override
    public String getName() {
        return "esql_query";
    }

    @Override
    public List<Route> routes() {
        return List.of(
            new Route(POST, "/_query"),
            // TODO: remove before release
            Route.builder(POST, "/_esql").deprecated("_esql endpoint has been deprecated in favour of _query", RestApiVersion.V_8).build()
        );
    }

    @Override
    protected RestChannelConsumer prepareRequest(RestRequest request, NodeClient client) throws IOException {
        // Measure response time beginning with now; also use this for logging.
        ThreadSafeStopWatch responseTimeStopWatch = new ThreadSafeStopWatch();

        EsqlQueryRequest esqlRequest;
        try (XContentParser parser = request.contentOrSourceParamParser()) {
            esqlRequest = EsqlQueryRequest.fromXContent(parser);
        }
        // Create some kind of query id so that we can correlate the beginning and the end of a query in the logs.
        String queryId = UUIDs.base64UUID();

        LOGGER.debug("Beginning execution of ESQL query.\nQuery ID: [{}]\nQuery string: [{}]", queryId, esqlRequest.query());

        return channel -> {
            RestCancellableNodeClient cancellableClient = new RestCancellableNodeClient(client, request.getHttpChannel());
            cancellableClient.execute(
                EsqlQueryAction.INSTANCE,
                esqlRequest,
                wrapWithLogging(
                    new EsqlResponseListener(channel, request, esqlRequest, responseTimeStopWatch),
                    queryId,
                    esqlRequest.query(),
                    responseTimeStopWatch
                )
            );
        };
    }

    /**
     * Log the execution time and query when handling an ESQL response.
     */
    private static ActionListener<EsqlQueryResponse> wrapWithLogging(
        ActionListener<EsqlQueryResponse> listener,
        String queryId,
        String esqlQuery,
        ThreadSafeStopWatch responseTimeStopWatch
    ) {
        return ActionListener.wrap(r -> {
            listener.onResponse(r);
            // At this point, the StopWatch should already have been stopped, so we log a consistent time.
            LOGGER.debug(
                "Finished execution of ESQL query.\nQuery ID: [{}]\nQuery string: [{}]\nExecution time: [{}]ms",
                queryId,
                esqlQuery,
                responseTimeStopWatch.stop().getMillis()
            );
        }, ex -> {
            // In case of failure, stop the time manually before sending out the response.
            long timeMillis = responseTimeStopWatch.stop().getMillis();
            listener.onFailure(ex);
            LOGGER.debug(
                "Failed execution of ESQL query.\nQuery ID: [{}]\nQuery string: [{}]\nExecution time: [{}]ms",
                queryId,
                esqlQuery,
                timeMillis
            );
        });
    }

    @Override
    protected Set<String> responseParams() {
        return Collections.singleton(URL_PARAM_DELIMITER);
    }
}
