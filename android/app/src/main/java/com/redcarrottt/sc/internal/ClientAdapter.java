package com.redcarrottt.sc.internal;

/* Copyright (c) 2017-2018. All rights reserved.
 *  Gyeonghwan Hong (redcarrottt@gmail.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import com.redcarrottt.testapp.Logger;

import java.nio.ByteBuffer;
import java.util.ArrayList;

import static com.redcarrottt.sc.internal.SegmentManager.kSegHeaderSize;
import static com.redcarrottt.sc.internal.SegmentManager.kSegRecv;
import static com.redcarrottt.sc.internal.SegmentManager.kSegSend;
import static com.redcarrottt.sc.internal.SegmentManager.kSegSize;

public class ClientAdapter {
    private final String kTag = "ClientAdapter";
    private final ClientAdapter self = this;

    // Main Functions: connect, disconnect, send, receive
    public void connect(ConnectResultListener listener, boolean isSendConnectMessage) {
        if (this.getState() != State.kDisconnected) {
            Logger.ERR(kTag, "It's already connected or connection/disconnection is in progress");
            listener.onConnectResult(false);
        }

        if (isSendConnectMessage) {
            Core.getInstance().sendRequestConnect((short) this.getId());
        }

        ConnectThread thread = new ConnectThread(listener);
        thread.start();
    }

    public void disconnect(DisconnectResultListener listener) {
        if (this.getState() != State.kConnected) {
            Logger.ERR(kTag, "It's already disconnected or connection/disconnection is in " +
                    "progress");
            listener.onDisconnectResult(false);
        }

        DisconnectThread thread = new DisconnectThread(listener);
        thread.start();
    }

    int send(byte[] dataBuffer, int dataLength) {
        if (this.getState() != State.kConnected) {
            Logger.ERR(kTag, "It's already disconnected or connection/disconnection is in " +
                    "progress");
            return -1;
        }

        if (this.mClientSocket == null) {
            return -2;
        }

        // Omit Implementing Statistics: SendDataSize
        return this.mClientSocket.send(dataBuffer, dataLength);
    }

    int receive(byte[] dataBuffer, int dataLength) {
        if (this.getState() != State.kConnected) {
            Logger.ERR(kTag, "It's already disconnected or connection/disconnection is in " +
                    "progress");
            return -1;
        }

        if (this.mClientSocket == null) {
            return -2;
        }

        // Omit Implementing Statistics: ReceiveDataSize
        return this.mClientSocket.receive(dataBuffer, dataLength);
    }

    // Connect/Disconnect Threads & Callbacks
    class ConnectThread extends Thread implements TurnOnResultListener,
            DiscoverAndConnectResultListener {
        @Override
        public void run() {
            Logger.VERB(kTag, self.getName() + "'s Connect Thread Spawned! (id:" + this.getId() +
                    ")");
            setState(ClientAdapter.State.kConnecting);

            if (self.mDevice == null || self.mP2PClient == null || self.mDevice == null) {
                this.onFail();
                return;
            }

            // Turn on device
            self.mDevice.holdAndTurnOn(this);
        }

        @Override
        public void onTurnOnResult(boolean isSuccess) {
            int deviceState = self.mDevice.getState();
            if (!isSuccess || deviceState != Device.State.kOn) {
                Logger.ERR(kTag, "Cannot connect the server adapter - turn-on fail: " + self
                        .getName());
                this.onFail();
                return;
            }

            Logger.VERB(kTag, "Turn on success: " + self.getName());

            // Discover and connect to server
            int p2pClientState = self.mP2PClient.getState();
            if (p2pClientState != P2PClient.State.kConnected) {
                self.mP2PClient.discoverAndConnect(this);
            }
        }

        @Override
        public void onDiscoverAndConnectResult(boolean isSuccess) {
            // Check the result of "Discover and connect"
            int p2pClientState = self.mP2PClient.getState();
            if (!isSuccess || p2pClientState != P2PClient.State.kConnected) {
                Logger.ERR(kTag, "Cannot connect the server adapter - allow fail:" + self.getName
                        ());
                self.mDevice.releaseAndTurnOff(null);
                this.onFail();
                return;
            }

            Logger.VERB(kTag, "P2P connect success: " + self.getName());

            this.mSocketOpenThread = new SocketOpenThread();
            this.mSocketOpenThread.start();
        }

        private SocketOpenThread mSocketOpenThread;

        class SocketOpenThread extends Thread {
            @Override
            public void run() {
                // Open client socket
                int socketState = self.mClientSocket.getState();
                if (socketState != ClientSocket.State.kOpened) {
                    boolean res = self.mClientSocket.open();

                    socketState = self.mClientSocket.getState();
                    if (!res || socketState != ClientSocket.State.kOpened) {
                        Logger.ERR(kTag, "Cannot connect the server adapter - socket open fail: "
                                + self.getName());
                        self.mP2PClient.disconnect(null);
                        self.mDevice.releaseAndTurnOff(null);
                        onFail();
                        return;
                    }
                }

                Logger.VERB(kTag, "Socket connect success: " + self.getName());

                // Run sender & receiver threads
                if (self.mSenderThread != null && !self.mSenderThread.isOn()) {
                    self.mSenderThread.start();
                }

                if (self.mReceiverThread != null && !self.mReceiverThread.isOn()) {
                    self.mReceiverThread.start();
                }

                // Report result success
                self.setState(ClientAdapter.State.kConnected);
                if (mResultListener != null) {
                    mResultListener.onConnectResult(true);
                    mResultListener = null;
                }
            }
        }

        private void onFail() {
            self.setState(ClientAdapter.State.kDisconnected);

            // Report result fail
            if (this.mResultListener != null) {
                this.mResultListener.onConnectResult(false);
                this.mResultListener = null;
            }
        }

        private ConnectResultListener mResultListener;

        ConnectThread(ConnectResultListener resultListener) {
            this.mResultListener = resultListener;
        }
    }

    class DisconnectThread extends Thread implements com.redcarrottt.sc.internal
            .DisconnectResultListener, TurnOffResultListener {
        @Override
        public void run() {
            Logger.VERB(kTag, self.getName() + "'s Disconnect Thread Spawned! (id:" + this.getId
                    () + ")");
            setState(ClientAdapter.State.kDisconnecting);

            // Finish sender & receiver threads
            if (self.mSenderThread != null) {
                self.mSenderThread.finish();
            }
            if (self.mReceiverThread != null) {
                self.mReceiverThread.finish();
            }

            // Close client socket
            if (self.mClientSocket == null) {
                this.onFail();
                return;
            }
            int socketState = self.mClientSocket.getState();
            if (socketState != ClientSocket.State.kClosed) {
                boolean res = self.mClientSocket.close();

                socketState = self.mClientSocket.getState();
                if (!res || socketState != ClientSocket.State.kClosed) {
                    Logger.ERR(kTag, "Cannot disconnect the server adapter - socket close fail: "
                            + "" + "" + self.getName());
                    this.onFail();
                    return;
                }
            }

            // P2P Disconnect
            int p2pClientState = self.mClientSocket.getState();
            if (p2pClientState != P2PClient.State.kDisconnected) {
                self.mP2PClient.disconnect(this);
            }
        }

        @Override
        public void onDisconnectResult(boolean isSuccess) {
            // Check the result of "P2P Disconnect"
            int p2pClientState = self.mP2PClient.getState();
            if (!isSuccess || p2pClientState != P2PClient.State.kDisconnected) {
                Logger.ERR(kTag, "Cannot disconnect the server adapter - disconnect P2P " +
                        "client fail: " + self.getName());
                this.onFail();
                return;
            }


            // Turn off device
            int deviceState = self.mDevice.getState();
            if (deviceState != Device.State.kOff) {
                self.mDevice.releaseAndTurnOff(this);
            }
        }

        @Override
        public void onTurnOffResult(boolean isSuccess) {
            int deviceState = self.mDevice.getState();
            if (!isSuccess || deviceState != Device.State.kOff) {
                Logger.ERR(kTag, "Cannot disconnect the server adapter - turn-off fail: " + self
                        .getName());
                this.onFail();
                return;
            }

            // Report result success
            self.setState(ClientAdapter.State.kDisconnected);
            if (this.mResultListener != null) {
                this.mResultListener.onDisconnectResult(true);
                this.mResultListener = null;
            }
        }

        private void onFail() {
            self.setState(ClientAdapter.State.kConnected);

            // Report result fail
            if (this.mResultListener != null) {
                this.mResultListener.onDisconnectResult(false);
                this.mResultListener = null;
            }
        }

        private DisconnectResultListener mResultListener;

        DisconnectThread(DisconnectResultListener resultListener) {
            this.mResultListener = resultListener;
        }
    }

    interface ConnectResultListener {
        void onConnectResult(boolean isSuccess);
    }

    interface DisconnectResultListener {
        void onDisconnectResult(boolean isSuccess);
    }

    // Thread Control Functions: enableSenderThread, enableReceiverThread
    void enableSenderThread() {
        this.mSenderThread = new SenderThread();
    }

    void enableReceiverThread() {
        this.mReceiveLoop = new ReceiveDataLoop();
        this.mReceiverThread = new ReceiverThread();
    }

    void enableReceiverThread(ReceiveLoop receiveLoop) {
        if (receiveLoop == null) {
            Logger.ERR(kTag, "ReceiverLoop is null!");
            return;
        }

        this.mReceiveLoop = receiveLoop;
        this.mReceiverThread = new ReceiverThread();
    }

    // Receiver Loop Interface
    interface ReceiveLoop {
        void receiveLoop(ClientAdapter adapter);
    }

    // Sender/Receiver Threads
    @SuppressWarnings("SynchronizeOnNonFinalField")
    class SenderThread extends Thread {
        @Override
        public void run() {
            synchronized (this.mIsOn) {
                this.mIsOn = true;
            }

            while (this.mIsOn) {
                SegmentManager sm = SegmentManager.getInstance();
                Segment segmentToSend;

                // At first, dequeue a segment from failed sending queue
                segmentToSend = sm.get_failed_sending();

                // If there is no failed segment, dequeue from send queue
                if (segmentToSend == null) {
                    segmentToSend = sm.dequeue(kSegSend);
                }

                int res = send(segmentToSend.data, kSegHeaderSize + kSegSize);
                if (res < 0) {
                    Logger.WARN(kTag, "Sending failed at " + ClientAdapter.this.getName());
                    sm.failed_sending(segmentToSend);
                    break;
                }
                sm.free_segment(segmentToSend);
            }

            disconnect(null);
            Logger.VERB(kTag, ClientAdapter.this.getName() + "'s Sender thread ends");
        }

        public void finish() {
            synchronized (this.mIsOn) {
                this.mIsOn = false;
            }
        }

        SenderThread() {
            this.mIsOn = false;
        }

        public boolean isOn() {
            return this.mIsOn;
        }

        private Boolean mIsOn;
    }

    @SuppressWarnings("SynchronizeOnNonFinalField")
    class ReceiverThread extends Thread {
        @Override
        public void run() {
            synchronized (this.mIsOn) {
                this.mIsOn = true;
            }
            mReceiveLoop.receiveLoop(self);
        }

        public void finish() {
            synchronized (this.mIsOn) {
                this.mIsOn = false;
            }
        }

        ReceiverThread() {
            this.mIsOn = false;
        }

        public boolean isOn() {
            return this.mIsOn;
        }

        private Boolean mIsOn;
    }

    class ReceiveDataLoop implements ReceiveLoop {
        @Override
        public void receiveLoop(ClientAdapter adapter) {
            while (adapter.mReceiverThread.isOn()) {
                SegmentManager sm = SegmentManager.getInstance();
                Segment segmentToReceive = sm.get_free_segment();
                int len = kSegSize + kSegHeaderSize;

                Logger.DEBUG(kTag, adapter.getName() + ": Receiving...");
                int res = adapter.receive(segmentToReceive.data, len);
                if (res < len) {
                    Logger.WARN(kTag, "Receiving failed at " + adapter.getName());
                    break;
                }

                // Read segment metadata
                ByteBuffer buffer = ByteBuffer.allocate(4);
                buffer.put(segmentToReceive.data, 0, 4);
                segmentToReceive.seq_no = buffer.getInt(0);

                buffer = ByteBuffer.allocate(4);
                buffer.put(segmentToReceive.data, 4, 4);
                segmentToReceive.flag_len = buffer.getInt(0);

                sm.enqueue(kSegRecv, segmentToReceive);
            }

            disconnect(null);
            Logger.VERB(kTag, ClientAdapter.this.getName() + "'s Receiver thread ends");
        }
    }

    private SenderThread mSenderThread;
    private ReceiverThread mReceiverThread;
    private ReceiveLoop mReceiveLoop;

    // Initialize
    public ClientAdapter(int id, String name) {
        this.mId = id;
        this.mName = name;
        this.mState = State.kDisconnected;
        this.mListeners = new ArrayList<>();
    }

    protected void initialize(Device device, P2PClient p2pClient, ClientSocket clientSocket) {
        this.mDevice = device;
        this.mP2PClient = p2pClient;
        this.mClientSocket = clientSocket;
    }

    // Attribute Getters
    public int getId() {
        return this.mId;
    }

    public String getName() {
        return this.mName;
    }

    // State
    class State {
        public static final int kDisconnected = 0;
        public static final int kConnecting = 1;
        public static final int kConnected = 2;
        public static final int kDisconnecting = 3;
    }

    @SuppressWarnings("SynchronizeOnNonFinalField")
    private int getState() {
        int state;
        synchronized (this.mState) {
            state = this.mState;
        }
        return state;
    }

    @SuppressWarnings("SynchronizeOnNonFinalField")
    private void setState(int newState) {
        int oldState;
        synchronized (this.mState) {
            oldState = this.mState;
            this.mState = newState;
        }

        for (StateListener listener : this.mListeners) {
            listener.onUpdateClientAdapterState(this, oldState, newState);
        }
    }

    // State Listener
    interface StateListener {
        void onUpdateClientAdapterState(ClientAdapter adapter, int oldState, int newState);
    }

    public void listenState(StateListener listener) {
        synchronized (this.mListeners) {
            this.mListeners.add(listener);
        }
    }

    // Attributes
    private int mId;
    private String mName;

    // State
    private Integer mState;

    // State Listener
    private final ArrayList<StateListener> mListeners;

    // Main Components : Device, P2PClient, ClientSocket
    private Device mDevice;
    private P2PClient mP2PClient;

    private ClientSocket mClientSocket;
}