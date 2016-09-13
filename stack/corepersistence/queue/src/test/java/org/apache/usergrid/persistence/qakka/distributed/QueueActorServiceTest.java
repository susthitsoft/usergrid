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

package org.apache.usergrid.persistence.qakka.distributed;

import com.datastax.driver.core.DataType;
import com.datastax.driver.core.ProtocolVersion;
import com.google.inject.Guice;
import com.google.inject.Injector;
import org.apache.cassandra.utils.UUIDGen;
import org.apache.usergrid.persistence.actorsystem.ActorSystemFig;
import org.apache.usergrid.persistence.qakka.AbstractTest;
import org.apache.usergrid.persistence.qakka.App;
import org.apache.usergrid.persistence.qakka.QakkaModule;
import org.apache.usergrid.persistence.qakka.core.CassandraClient;
import org.apache.usergrid.persistence.qakka.core.CassandraClientImpl;
import org.apache.usergrid.persistence.qakka.core.Queue;
import org.apache.usergrid.persistence.qakka.core.QueueManager;
import org.apache.usergrid.persistence.qakka.core.impl.InMemoryQueue;
import org.apache.usergrid.persistence.qakka.serialization.queuemessages.DatabaseQueueMessage;
import org.apache.usergrid.persistence.qakka.serialization.queuemessages.DatabaseQueueMessageBody;
import org.apache.usergrid.persistence.qakka.serialization.queuemessages.QueueMessageSerialization;
import org.junit.Assert;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.ObjectInputStream;
import java.nio.ByteBuffer;
import java.util.Collection;
import java.util.UUID;


public class QueueActorServiceTest extends AbstractTest {
    private static final Logger logger = LoggerFactory.getLogger( QueueActorServiceTest.class );

    protected Injector myInjector = null;

    @Override
    protected Injector getInjector() {
        if ( myInjector == null ) {
            myInjector = Guice.createInjector( new QakkaModule() );
        }
        return myInjector;
    }


    @Test
    public void testBasicOperation() throws Exception {

        CassandraClient cassandraClient = getInjector().getInstance( CassandraClientImpl.class );
        cassandraClient.getSession();

        ActorSystemFig actorSystemFig = getInjector().getInstance( ActorSystemFig.class );
        String region = actorSystemFig.getRegionLocal();

        App app = getInjector().getInstance( App.class );
        app.start( "localhost", getNextAkkaPort(), region );

        DistributedQueueService distributedQueueService = getInjector().getInstance( DistributedQueueService.class );
        QueueMessageSerialization serialization = getInjector().getInstance( QueueMessageSerialization.class );

        String queueName = "testqueue_" + UUID.randomUUID();
        QueueManager queueManager = getInjector().getInstance( QueueManager.class );
        queueManager.createQueue( new Queue( queueName, "test-type", region, region, 0L, 5, 10, null ));

        // send 1 queue message, get back one queue message
        UUID messageId = UUIDGen.getTimeUUID();

        final String data = "my test data";
        final DatabaseQueueMessageBody messageBody = new DatabaseQueueMessageBody(
                DataType.serializeValue( data, ProtocolVersion.NEWEST_SUPPORTED ), "text/plain" );
        serialization.writeMessageData( messageId, messageBody );

        distributedQueueService.sendMessageToRegion(
                queueName, region, region, messageId, null, null);

        distributedQueueService.refresh();
        Thread.sleep(1000);

        Collection<DatabaseQueueMessage> qmReturned = distributedQueueService.getNextMessages( queueName, 1 );
        Assert.assertEquals( 1, qmReturned.size() );

        DatabaseQueueMessage dqm = qmReturned.iterator().next();
        DatabaseQueueMessageBody dqmb = serialization.loadMessageData( dqm.getMessageId() );
        ByteBuffer blob = dqmb.getBlob();

        String returnedData = new String( blob.array(), "UTF-8");
//        ByteArrayInputStream bais = new ByteArrayInputStream( blob.array() );
//        ObjectInputStream ios = new ObjectInputStream( bais );
//        String returnedData = (String)ios.readObject();

        Assert.assertEquals( data, returnedData );

    }


    @Test
    public void testGetMultipleQueueMessages() throws InterruptedException {

        CassandraClient cassandraClient = getInjector().getInstance( CassandraClientImpl.class );
        cassandraClient.getSession();

        ActorSystemFig actorSystemFig = getInjector().getInstance( ActorSystemFig.class );
        String region = actorSystemFig.getRegionLocal();

        App app = getInjector().getInstance( App.class );
        app.start("localhost", getNextAkkaPort(), region);

        DistributedQueueService distributedQueueService = getInjector().getInstance( DistributedQueueService.class );
        QueueMessageSerialization serialization = getInjector().getInstance( QueueMessageSerialization.class );
        InMemoryQueue inMemoryQueue             = getInjector().getInstance( InMemoryQueue.class );

        String queueName = "testqueue_" + UUID.randomUUID();
        QueueManager queueManager = getInjector().getInstance( QueueManager.class );
        queueManager.createQueue(
                new Queue( queueName, "test-type", region, region, 0L, 5, 10, null ));

        for ( int i=0; i<100; i++ ) {

            UUID messageId = UUIDGen.getTimeUUID();

            final String data = "my test data";
            final DatabaseQueueMessageBody messageBody = new DatabaseQueueMessageBody(
                    DataType.serializeValue( data, ProtocolVersion.NEWEST_SUPPORTED ), "text/plain" );
            serialization.writeMessageData( messageId, messageBody );

            distributedQueueService.sendMessageToRegion(
                    queueName, region, region, messageId , null, null);
        }

        int maxRetries = 15;
        int retries = 0;
        while ( retries++ < maxRetries ) {
            distributedQueueService.refresh();
            Thread.sleep( 3000 );
            if (inMemoryQueue.size( queueName ) == 100) {
                break;
            }
        }

        Assert.assertEquals( 100, inMemoryQueue.size( queueName ) );

        Assert.assertEquals( 25, distributedQueueService.getNextMessages( queueName, 25 ).size() );
        Assert.assertEquals( 75, inMemoryQueue.size( queueName ) );

        Assert.assertEquals( 25, distributedQueueService.getNextMessages( queueName, 25 ).size() );
        Assert.assertEquals( 50, inMemoryQueue.size( queueName ) );

        Assert.assertEquals( 25, distributedQueueService.getNextMessages( queueName, 25 ).size() );
        Assert.assertEquals( 25, inMemoryQueue.size( queueName ) );

        Assert.assertEquals( 25, distributedQueueService.getNextMessages( queueName, 25 ).size() );
        Assert.assertEquals( 0, inMemoryQueue.size( queueName ) );

    }

}
