package guide.config;

import guide.data.comments.InMemoryCommentRepository;
import guide.domain.comments.CommentRepository;
import guide.domain.graphql.ExecuteGraphqlQuery;
import guide.presentation.graphql.GraphqlFailureResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.graphql.ExecutionGraphQlService;
import org.springframework.graphql.execution.DataFetcherExceptionResolver;

/**
 * Topic 13 (graphql) wiring — the comments substrate and the playground's
 * composition root, every wire explicit:
 *
 * - ONE comment port bean (the in-memory adapter with its deterministic
 *   27-comment seed and its N+1-proof call counters). Phase 6 swaps this
 *   for a PostgreSQL adapter behind the same port — the batch-shaped
 *   listForPosts method becomes one IN-clause query, and the GraphQL
 *   resolvers above it do not notice.
 * - The failure resolver as a DataFetcherExceptionResolver bean — Boot's
 *   GraphQL autoconfiguration collects every such bean into the
 *   GraphQlSource it builds from the schema files and @Controller
 *   resolvers, which is the whole spring-for-graphql integration story:
 *   beans in, engine out.
 * - The execute use case over the autoconfigured ExecutionGraphQlService —
 *   the same engine the /graphql endpoint serves, driven programmatically
 *   by the playground's run endpoint.
 */
@Configuration
public class GraphqlDemoConfig {

    @Bean
    CommentRepository commentRepository() {
        return new InMemoryCommentRepository();
    }

    @Bean
    DataFetcherExceptionResolver graphqlFailureResolver() {
        return new GraphqlFailureResolver();
    }

    @Bean
    ExecuteGraphqlQuery executeGraphqlQuery(ExecutionGraphQlService graphQlService) {
        return new ExecuteGraphqlQuery(graphQlService);
    }
}
