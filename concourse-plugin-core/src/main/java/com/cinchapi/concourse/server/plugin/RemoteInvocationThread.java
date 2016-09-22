/*
 * Copyright (c) 2013-2016 Cinchapi Inc.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.cinchapi.concourse.server.plugin;

import java.nio.ByteBuffer;
import java.util.concurrent.ConcurrentMap;

import javax.annotation.Nullable;

import com.cinchapi.common.reflect.Reflection;
import com.cinchapi.concourse.server.plugin.io.PluginSerializable;
import com.cinchapi.concourse.server.plugin.io.PluginSerializer;
import com.cinchapi.concourse.server.plugin.io.SharedMemory;
import com.cinchapi.concourse.thrift.AccessToken;
import com.cinchapi.concourse.thrift.ComplexTObject;
import com.cinchapi.concourse.thrift.TransactionToken;

/**
 * A daemon {@link Thread} that is responsible for processing a
 * {@link RemoteMethodRequest} and sending a {@link RemoteMethodResponse} on
 * the appropriate {@link SharedMemory channel}.
 * 
 * @author Jeff Nelson
 */
final class RemoteInvocationThread extends Thread {

    /**
     * A collection of responses from the upstream service. Made available for
     * async processing.
     */
    protected final ConcurrentMap<AccessToken, RemoteMethodResponse> responses;

    /**
     * The local object that contains the methods to invoke.
     */
    private final Object invokable;

    /**
     * The {@link SharedMemory} segment that is used for broadcasting the
     * response.
     */
    private final SharedMemory outgoing;

    /**
     * The request that is being processed by this thread.
     */
    private final RemoteMethodRequest request;

    /**
     * A lazily loaded {@link PluginSerializer} that is used to transform
     * {@link PluginSerializable} data to binary.
     */
    private PluginSerializer serializer = null;

    /**
     * A flag that indicates whether thrift arguments should be passed when
     * invoking a local method on behalf of a remote request.
     */
    private final boolean useLocalThriftArgs;

    /**
     * Construct a new instance.
     * 
     * @param request
     * @param outgoing
     * @param invokable
     * @param useLocalThriftArgs
     * @param responses
     */
    public RemoteInvocationThread(RemoteMethodRequest request,
            SharedMemory outgoing, Object invokable,
            boolean useLocalThriftArgs,
            ConcurrentMap<AccessToken, RemoteMethodResponse> responses) {
        this.request = request;
        this.outgoing = outgoing;
        this.invokable = invokable;
        this.useLocalThriftArgs = useLocalThriftArgs;
        this.responses = responses;
        setDaemon(true);
    }

    /**
     * Return the {@link AccessToken} associated with the user session that owns
     * this thread.
     * 
     * @return the associated {@link AccessToken}
     */
    public AccessToken accessToken() {
        return request.creds;
    }

    /**
     * Return the name of the most recent environment associated with the user
     * session that owns this thread.
     * 
     * @return the environment
     */
    public String environment() {
        return request.environment;
    }

    /**
     * Return the {@link SharedMemory} segment that is used to send any outgoing
     * messages.
     * 
     * @return the request channel
     */
    public SharedMemory outgoing() {
        return outgoing;
    }

    @Override
    public final void run() {
        int argCount = request.args.size() + (useLocalThriftArgs ? 3 : 0);
        Object[] jargs = new Object[argCount];
        int i = 0;
        for (; i < request.args.size(); ++i) {
            Object jarg = request.args.get(i).getJavaObject();
            if(jarg instanceof ByteBuffer) {
                // If any of the arguments are BINARY, we assume that the caller
                // manually serialized a PluginSerializable object, so we must
                // try to convert to the actual object so that the method is
                // actually called.
                jarg = serializer().deserialize((ByteBuffer) jarg);
            }
            jargs[i] = jarg;
        }
        if(useLocalThriftArgs) {
            jargs[i++] = request.creds;
            jargs[i++] = request.transaction;
            jargs[i++] = request.environment;
        }
        RemoteMethodResponse response = null;
        try {
            if(request.method.equals("getServerVersion")) {
                // getServerVersion, for some reason doesn't take an
                // arguments...not even the standard meta arguments that all
                // other methods take
                jargs = new Object[0];
            }
            Object result0 = Reflection
                    .callIf((method) -> method.isAccessible()
                            && !method
                                    .isAnnotationPresent(PluginRestricted.class),
                            invokable, request.method, jargs);
            if(result0 instanceof PluginSerializable) {
                // CON-509: PluginSerializable objects must be wrapped as BINARY
                // within a ComplexTObject
                result0 = serializer().serialize(result0);
            }
            ComplexTObject result = ComplexTObject.fromJavaObject(result0);
            response = new RemoteMethodResponse(request.creds, result);
        }
        catch (Exception e) {
            response = new RemoteMethodResponse(request.creds, e);
        }
        ByteBuffer buffer = serializer().serialize(response);
        outgoing.write(buffer);
    }

    /**
     * Return the most recent {@link TransactionToken} associated with the user
     * session that owns this thread.
     * 
     * @return the {@link TransactionToken}
     */
    @Nullable
    public TransactionToken transactionToken() {
        return request.transaction;
    }

    /**
     * If necessary load and then return the {@link #serializer}.
     */
    private PluginSerializer serializer() {
        if(serializer == null) {
            serializer = new PluginSerializer();
        }
        return serializer;
    }

}
