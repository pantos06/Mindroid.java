/*
 * Copyright (C) 2006 The Android Open Source Project
 * Copyright (C) 2013 Daniel Himmelein
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package mindroid.os;

import java.net.URI;
import mindroid.util.concurrent.Promise;

/**
 * Base interface for a remotable object, the core part of a lightweight remote procedure call
 * mechanism designed for high performance when performing in-process and cross-process calls. This
 * interface describes the abstract protocol for interacting with a remotable object. Do not
 * implement this interface directly, instead extend from {@link Binder}.
 *
 * @see Binder
 */
public interface IBinder {
    /**
     * Flag to {@link #transact}: this is a one-way call, meaning that the caller returns
     * immediately, without waiting for a result from the callee. Applies only if the caller and
     * callee are in different processes.
     */
    public static final int FLAG_ONEWAY = 0x00000001;

    /**
     * Returns the binder's id.
     */
    public long getId();

    /**
     * Returns the binder's URI.
     */
    public URI getUri();

    /**
     * Get the canonical name of the interface supported by this binder.
     */
    public String getInterfaceDescriptor();

    /**
     * Attempt to retrieve a local implementation of an interface for this Binder object. If null is
     * returned, you will need to instantiate a proxy class to marshall calls through the transact()
     * method.
     */
    public IInterface queryLocalInterface(String descriptor);

    /**
     * Perform a generic operation with the object.
     *
     * @param what The action to perform.
     * @param data data to send to the target. Must not be null.
     * @param flags Additional operation flags. Either 0 for a normal RPC, or {@link #FLAG_ONEWAY}
     * for a one-way RPC.
     */
    public Promise<Parcel> transact(int what, Parcel data, int flags) throws RemoteException;

    /**
     * Perform a lightweight operation with the object.
     *
     * @param what The action to perform.
     * @param num A number to send to the target.
     * @param obj An object to send to the target.
     * @param data data to send to the target.
     * @param flags Additional operation flags. Either 0 for a normal RPC, or {@link #FLAG_ONEWAY}
     * for a one-way RPC.
     */
    public void transact(int what, int num, Object obj, Bundle data, Promise<?> promise, int flags) throws RemoteException;

    public interface Supervisor {
        public void onExit(int reason);
    }

    public void link(Supervisor supervisor, int flags) throws RemoteException;

    public boolean unlink(Supervisor supervisor, int flags);
}
