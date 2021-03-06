package com.cyclops;

import com.cyclops.config.ClusterConfig;
import com.cyclops.config.CyclopsConfiguration;
import com.cyclops.healthcheck.HealthCheck;
import com.cyclops.pubsub.TopicPublisher;
import com.cyclops.resources.EventPublisherResource;
import com.cyclops.resources.TopicInfoResource;
import com.cyclops.streaming.AtmosphereUtil;
import com.cyclops.streaming.NotificationResource;
import com.hazelcast.config.Config;
import com.hazelcast.core.Hazelcast;
import com.hazelcast.core.HazelcastInstance;
import com.yammer.dropwizard.Service;
import com.yammer.dropwizard.assets.AssetsBundle;
import com.yammer.dropwizard.config.Bootstrap;
import com.yammer.dropwizard.config.Environment;
import com.yammer.dropwizard.config.HttpConfiguration;
import org.atmosphere.cpr.AtmosphereServlet;
import org.eclipse.jetty.servlets.CrossOriginFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.ws.rs.core.MediaType;
import java.util.ArrayList;
import java.util.concurrent.TimeUnit;

/**
 * User: Santanu Sinha (santanu.sinha@flipkart.com)
 * Date: 14/09/13
 * Time: 2:15 PM
 */
public class CyclopsService extends Service<CyclopsConfiguration> {
    private static final Logger logger = LoggerFactory.getLogger(CyclopsService.class.getSimpleName());
    private String hostName;

    public CyclopsService(String hostName) {
        super();
        this.hostName = hostName;
    }

    @Override
    public void initialize(Bootstrap<CyclopsConfiguration> bootstrap) {
        bootstrap.setName("cyclops");
        bootstrap.addBundle(new AssetsBundle("/console/", "/"));
    }

    void initializeAtmosphere(CyclopsConfiguration configuration, Environment environment) {
        AtmosphereServlet atmosphereServlet = new AtmosphereServlet();
        atmosphereServlet.framework().addInitParameter("com.sun.jersey.config.property.packages",
                                                            NotificationResource.class.getPackage().getName());
        atmosphereServlet.framework().addInitParameter("org.atmosphere.websocket.messageContentType",
                                                            MediaType.APPLICATION_JSON);
        environment.addServlet(atmosphereServlet, "/cyclops/notify/*");
    }

    @Override
    public void run(CyclopsConfiguration configuration, Environment environment) throws Exception {
        ClusterConfig clusterConfig = configuration.getCluster();
        ArrayList<HazelcastInstance> hazelcastInstances
                = new ArrayList<HazelcastInstance>(clusterConfig.getNumMembersPerNode());
        ArrayList<TopicPublisher> publishers = new ArrayList<TopicPublisher>(clusterConfig.getNumMembersPerNode());
        for(int i = 0; i < clusterConfig.getNumMembersPerNode(); i++) {
            Config hzConfig = new Config();
            hzConfig.getGroupConfig().setName(clusterConfig.getName());
            hzConfig.setInstanceName(String.format("%s-%d", hostName, System.currentTimeMillis()));
            if(clusterConfig.isDisableMulticast()) {
                hzConfig.getNetworkConfig().getJoin().getMulticastConfig().setEnabled(false);
                for(String member: clusterConfig.getMembers()) {
                    hzConfig.getNetworkConfig().getJoin().getTcpIpConfig().addMember(member);
                }
                hzConfig.getNetworkConfig().getJoin().getTcpIpConfig().setEnabled(true);
            }
            HazelcastInstance hazelcast = Hazelcast.newHazelcastInstance(hzConfig);
            hazelcastInstances.add(hazelcast);
            //hazelcast.getCluster().addMembershipListener(new ClusterListener());
            //TODO::ADD N PUBLISHERS PER INSTANCE
            TopicPublisher publisher = new TopicPublisher(hazelcast);
            publishers.add(publisher);
        }
        EventPublisherResource publisherResource = new EventPublisherResource(publishers);
        environment.addResource(publisherResource);
        environment.addResource(new TopicInfoResource());
        environment.addHealthCheck(new HealthCheck());
        AtmosphereUtil.init(hazelcastInstances, environment.managedExecutorService("streamer", 10 ,10, 1, TimeUnit.SECONDS));
        //environment.addResource(new NotificationResource());
        //environment.managedExecutorService()
        environment.addFilter(CrossOriginFilter.class, "/*");
        //FilterBuilder fconfig = environment.addFilter(new AtmosphereFilter(), "/cyclops/notify/*");
        //fconfig.setInitParam("com.sun.jersey.config.property.packages", "com.cyclops.streaming");
        initializeAtmosphere(configuration, environment);
        configuration.getHttpConfiguration().setRootPath("/cyclops/*");
        configuration.getHttpConfiguration().setConnectorType(HttpConfiguration.ConnectorType.NONBLOCKING);
    }
}
