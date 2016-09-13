/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.usergrid.persistence.qakka.distributed.actors;


import akka.actor.UntypedActor;
import com.codahale.metrics.Timer;
import com.datastax.driver.core.utils.UUIDs;
import com.google.inject.Injector;
import org.apache.usergrid.persistence.actorsystem.ActorSystemFig;
import org.apache.usergrid.persistence.qakka.App;
import org.apache.usergrid.persistence.qakka.MetricsService;
import org.apache.usergrid.persistence.qakka.QakkaFig;
import org.apache.usergrid.persistence.qakka.core.CassandraClient;
import org.apache.usergrid.persistence.qakka.core.CassandraClientImpl;
import org.apache.usergrid.persistence.qakka.distributed.messages.ShardCheckRequest;
import org.apache.usergrid.persistence.qakka.exceptions.NotFoundException;
import org.apache.usergrid.persistence.qakka.exceptions.QakkaRuntimeException;
import org.apache.usergrid.persistence.qakka.serialization.sharding.Shard;
import org.apache.usergrid.persistence.qakka.serialization.sharding.ShardCounterSerialization;
import org.apache.usergrid.persistence.qakka.serialization.sharding.ShardIterator;
import org.apache.usergrid.persistence.qakka.serialization.sharding.ShardSerialization;
import org.apache.usergrid.persistence.qakka.serialization.sharding.impl.ShardCounterSerializationImpl;
import org.apache.usergrid.persistence.qakka.serialization.sharding.impl.ShardSerializationImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;
import java.util.UUID;


public class ShardAllocator extends UntypedActor {
    private static final Logger logger = LoggerFactory.getLogger( ShardAllocator.class );

    private final String queueName;

    private final QakkaFig qakkaFig;
    private final ActorSystemFig            actorSystemFig;
    private final ShardSerialization        shardSerialization;
    private final ShardCounterSerialization shardCounterSerialization;
    private final MetricsService            metricsService;
    private final CassandraClient           cassandraClient;


    public ShardAllocator( String queueName ) {
        this.queueName = queueName;

        Injector injector = App.INJECTOR;

        this.qakkaFig                  = injector.getInstance( QakkaFig.class );
        this.shardCounterSerialization = injector.getInstance( ShardCounterSerializationImpl.class );
        this.shardSerialization        = injector.getInstance( ShardSerializationImpl.class );
        this.actorSystemFig            = injector.getInstance( ActorSystemFig.class );
        this.metricsService            = injector.getInstance( MetricsService.class );
        this.cassandraClient           = injector.getInstance( CassandraClientImpl.class );

        logger.debug( "Created shard allocator for queue {}", queueName );
    }


    @Override
    public void onReceive( Object message ) throws Exception {

        if ( message instanceof ShardCheckRequest) {

            ShardCheckRequest request = (ShardCheckRequest) message;

            if (!request.getQueueName().equals( queueName )) {
                throw new QakkaRuntimeException(
                        "ShardAllocator for " + queueName + ": Incorrect queueName " + request.getQueueName() );
            }

            // check both types of shard
            checkLatestShard( Shard.Type.DEFAULT );
            checkLatestShard( Shard.Type.INFLIGHT );

        } else {
            unhandled( message );
        }

    }

    private void checkLatestShard( Shard.Type type ) {

        Timer.Context timer = metricsService.getMetricRegistry().timer( MetricsService.ALLOCATE_TIME).time();

        try {

            String region = actorSystemFig.getRegionLocal();

            // find newest shard

            ShardIterator shardIterator = new ShardIterator(
                    cassandraClient, queueName, region, type, Optional.empty() );

            Shard shard = null;
            while (shardIterator.hasNext()) {
                shard = shardIterator.next();
            }

            if (shard == null) {
                logger.warn( "No shard found for {}, {}, {}", queueName, region, type );
                return;
            }

            // if its count is greater than 90% of max shard size, then allocate a new shard

            long counterValue = 0;
            try {
                counterValue = shardCounterSerialization.getCounterValue( queueName, type, shard.getShardId() );
            } catch ( NotFoundException ignored ) {}

            if (counterValue > (0.9 * qakkaFig.getMaxShardSize())) {

                // Create UUID from a UNIX timestamp via DataStax utility
                // https://docs.datastax.com/en/drivers/java/2.0/com/datastax/driver/core/utils/UUIDs.html
                UUID futureUUID = UUIDs.startOf(
                        System.currentTimeMillis() + qakkaFig.getShardAllocationAdvanceTimeMillis());

                Shard newShard = new Shard( queueName, region, type, shard.getShardId() + 1, futureUUID );
                shardSerialization.createShard( newShard );
                shardCounterSerialization.incrementCounter( queueName, type, newShard.getShardId(), 0 );

                logger.info("{} Created new shard for queue {} shardId {} timestamp {} counterValue {}",
                        this.hashCode(), queueName, shard.getShardId(), futureUUID.timestamp(), counterValue );
            }

        } catch ( Throwable t ) {
            logger.error("Error while checking shard allocations", t);

        } finally {
            timer.close();
        }

    }
}
