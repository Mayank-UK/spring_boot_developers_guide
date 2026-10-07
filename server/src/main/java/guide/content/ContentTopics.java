package guide.content;

import guide.content.topics.AopTopic;
import guide.content.topics.ApiDesignTopic;
import guide.content.topics.ApiDocumentationTopic;
import guide.content.topics.ArchitectureTopic;
import guide.content.topics.AuthenticationTopic;
import guide.content.topics.AuthorizationTopic;
import guide.content.topics.BackgroundJobsTopic;
import guide.content.topics.CachingTopic;
import guide.content.topics.DebuggingTopic;
import guide.content.topics.DependencyManagementTopic;
import guide.content.topics.DeploymentTopic;
import guide.content.topics.EnvironmentsTopic;
import guide.content.topics.ExceptionHandlingTopic;
import guide.content.topics.FilesMediaTopic;
import guide.content.topics.FiltersInterceptorsTopic;
import guide.content.topics.GraphqlTopic;
import guide.content.topics.GrpcTopic;
import guide.content.topics.HttpWebTopic;
import guide.content.topics.InternationalizationTopic;
import guide.content.topics.JavaLanguageTopic;
import guide.content.topics.JdbcSqlTopic;
import guide.content.topics.JvmTopic;
import guide.content.topics.MongodbTopic;
import guide.content.topics.MessagingTopic;
import guide.content.topics.ObservabilityTopic;
import guide.content.topics.PerformanceTopic;
import guide.content.topics.ReliabilityTopic;
import guide.content.topics.SpringBootFundamentalsTopic;
import guide.content.topics.RequestResponseTopic;
import guide.content.topics.RedisTopic;
import guide.content.topics.RealtimeTopic;
import guide.content.topics.RoutingTopic;
import guide.content.topics.SecurityTopic;
import guide.content.topics.ScalingTopic;
import guide.content.topics.SpringCoreTopic;
import guide.content.topics.SpringDataJpaTopic;
import guide.content.topics.SqlSchemaTopic;
import guide.content.topics.TestingTopic;
import guide.content.topics.ValidationTopic;
import guide.content.topics.WebhooksTopic;
import java.util.List;

/**
 * Aggregates every topic the guide serves. The registry validation plus
 * SnippetSourcesTest pick up each entry automatically — one file per topic
 * under content/topics/ (built with TopicBlocks), one entry here, in
 * curriculum order.
 */
public final class ContentTopics {

    private ContentTopics() {
    }

    public static List<Topic> all() {
        return List.of(
                JavaLanguageTopic.topic(),
                JvmTopic.topic(),
                HttpWebTopic.topic(),
                SpringBootFundamentalsTopic.topic(),
                SpringCoreTopic.topic(),
                AopTopic.topic(),
                RoutingTopic.topic(),
                FiltersInterceptorsTopic.topic(),
                RequestResponseTopic.topic(),
                ValidationTopic.topic(),
                ApiDesignTopic.topic(),
                ApiDocumentationTopic.topic(),
                GraphqlTopic.topic(),
                WebhooksTopic.topic(),
                GrpcTopic.topic(),
                SqlSchemaTopic.topic(),
                JdbcSqlTopic.topic(),
                SpringDataJpaTopic.topic(),
                MongodbTopic.topic(),
                RedisTopic.topic(),
                CachingTopic.topic(),
                MessagingTopic.topic(),
                AuthenticationTopic.topic(),
                AuthorizationTopic.topic(),
                SecurityTopic.topic(),
                FilesMediaTopic.topic(),
                InternationalizationTopic.topic(),
                RealtimeTopic.topic(),
                BackgroundJobsTopic.topic(),
                ExceptionHandlingTopic.topic(),
                DependencyManagementTopic.topic(),
                ArchitectureTopic.topic(),
                ObservabilityTopic.topic(),
                TestingTopic.topic(),
                DebuggingTopic.topic(),
                EnvironmentsTopic.topic(),
                DeploymentTopic.topic(),
                PerformanceTopic.topic(),
                ReliabilityTopic.topic(),
                ScalingTopic.topic());
    }
}
