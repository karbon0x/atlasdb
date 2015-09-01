package com.palantir.atlasdb.keyvalue.remoting;

import io.dropwizard.Configuration;
import io.dropwizard.testing.DropwizardTestSupport;
import io.dropwizard.testing.junit.DropwizardClientRule;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.NavigableMap;
import java.util.Set;

import org.apache.commons.lang.ArrayUtils;
import org.assertj.core.util.Sets;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.datatype.guava.GuavaModule;
import com.google.common.base.Supplier;
import com.google.common.collect.Maps;
import com.google.common.primitives.UnsignedBytes;
import com.palantir.atlasdb.keyvalue.api.KeyValueService;
import com.palantir.atlasdb.keyvalue.impl.InMemoryKeyValueService;
import com.palantir.atlasdb.keyvalue.partition.api.DynamicPartitionMap;
import com.palantir.atlasdb.keyvalue.partition.endpoint.InMemoryKeyValueEndpoint;
import com.palantir.atlasdb.keyvalue.partition.endpoint.KeyValueEndpoint;
import com.palantir.atlasdb.keyvalue.partition.endpoint.SimpleKeyValueEndpoint;
import com.palantir.atlasdb.keyvalue.partition.map.DynamicPartitionMapImpl;
import com.palantir.atlasdb.keyvalue.partition.map.PartitionMapService;
import com.palantir.atlasdb.keyvalue.partition.map.PartitionMapServiceImpl;
import com.palantir.atlasdb.server.InboxPopulatingContainerRequestFilter;
import com.palantir.common.base.Throwables;
import com.palantir.util.Pair;

public class Utils {

    public static final SimpleModule module = RemotingKeyValueService.kvsModule();
    public static final ObjectMapper mapper = RemotingKeyValueService.kvsMapper();

    public static DropwizardClientRule getRemoteKvsRule(KeyValueService remoteKvs) {
        DropwizardClientRule rule = new DropwizardClientRule(remoteKvs,
                KeyAlreadyExistsExceptionMapper.instance(),
                InsufficientConsistencyExceptionMapper.instance(),
                VersionTooOldExceptionMapper.instance(),
                new InboxPopulatingContainerRequestFilter(mapper));
        return rule;
    }

    public static void setupRuleHacks(DropwizardClientRule rule) {
        try {
            Field field = rule.getClass().getDeclaredField("testSupport");
            field.setAccessible(true);
            @SuppressWarnings("unchecked")
            DropwizardTestSupport<Configuration> testSupport = (DropwizardTestSupport<Configuration>) field.get(rule);
            ObjectMapper mapper = testSupport.getEnvironment().getObjectMapper();
            mapper.registerModule(Utils.module);
            mapper.registerModule(new GuavaModule());
            testSupport.getApplication();
        } catch (Exception e) {
            throw Throwables.throwUncheckedException(e);
        }
    }
    
    public static DynamicPartitionMap createNewMap(Collection<? extends Pair<RemoteKvs, RemotePms>> endpoints) {
    	ArrayList<Byte> keyList = new ArrayList<>();
    	NavigableMap<byte[], KeyValueEndpoint> ring = Maps.newTreeMap(UnsignedBytes.lexicographicalComparator());
    	keyList.add((byte) 0);
    	for (Pair<RemoteKvs, RemotePms> p : endpoints) {
    		SimpleKeyValueEndpoint kvs = new SimpleKeyValueEndpoint(p.lhSide.rule.baseUri().toString(), p.rhSide.rule.baseUri().toString());
    		byte[] key = ArrayUtils.toPrimitive(keyList.toArray(new Byte[keyList.size()]));
    		ring.put(key, kvs);
            keyList.add((byte) 0);
    	}
    	return DynamicPartitionMapImpl.create(ring);
    }
    
    public static DynamicPartitionMap createInMemoryMap(Collection<? extends KeyValueService> services) {
    	ArrayList<Byte> keyList = new ArrayList<>();
    	NavigableMap<byte[], KeyValueEndpoint> ring = Maps.newTreeMap(UnsignedBytes.lexicographicalComparator());
    	keyList.add((byte) 0);
    	for (KeyValueService kvs : services) {
    		KeyValueEndpoint endpoint = InMemoryKeyValueEndpoint.create(kvs, new PartitionMapServiceImpl());
    		byte[] key = ArrayUtils.toPrimitive(keyList.toArray(new Byte[keyList.size()]));
    		ring.put(key, endpoint);
            keyList.add((byte) 0);
    	}
    	DynamicPartitionMap partitionMap = DynamicPartitionMapImpl.create(ring);
    	for (KeyValueEndpoint endpoint : ring.values()) {
    		endpoint.partitionMapService().update(partitionMap);
    	}
    	return partitionMap;
    }

    public static class RemoteKvs {
        public final KeyValueService inMemoryKvs = new InMemoryKeyValueService(false);
        public final KeyValueService remoteKvs;
        public final DropwizardClientRule rule;

        public RemoteKvs(final RemotePms remotePms) {
            remoteKvs = RemotingKeyValueService.createServerSide(inMemoryKvs, new Supplier<Long>() {
                @Override
                public Long get() {
                    Long version = RemotingPartitionMapService.createClientSide(remotePms.rule.baseUri().toString()).getVersion();
                    return version;
                }
            });
            rule = Utils.getRemoteKvsRule(remoteKvs);
        }
    }

    public static class RemotePms {
        public PartitionMapService service = new PartitionMapServiceImpl();
        public DropwizardClientRule rule = new DropwizardClientRule(service);
    }

}