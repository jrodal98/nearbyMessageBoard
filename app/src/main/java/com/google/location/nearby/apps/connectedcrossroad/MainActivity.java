package com.google.location.nearby.apps.connectedcrossroad;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Bundle;

import androidx.annotation.CallSuper;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.appcompat.app.AppCompatActivity;

import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.gms.nearby.Nearby;
import com.google.android.gms.nearby.connection.AdvertisingOptions;
import com.google.android.gms.nearby.connection.ConnectionInfo;
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback;
import com.google.android.gms.nearby.connection.ConnectionResolution;
import com.google.android.gms.nearby.connection.ConnectionsClient;
import com.google.android.gms.nearby.connection.ConnectionsStatusCodes;
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo;
import com.google.android.gms.nearby.connection.DiscoveryOptions;
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback;
import com.google.android.gms.nearby.connection.Payload;
import com.google.android.gms.nearby.connection.PayloadCallback;
import com.google.android.gms.nearby.connection.PayloadTransferUpdate;
import com.google.android.gms.nearby.connection.PayloadTransferUpdate.Status;
import com.google.android.gms.nearby.connection.Strategy;
import com.google.android.gms.tasks.OnFailureListener;

import java.io.IOException;
import java.util.HashSet;


/**
 * Activity controlling the Message Board
 */
public class MainActivity extends AppCompatActivity {

    private static final String TAG = "connectedcrossroad";
    private static final String LATENCY = "latency_tag";

    private static final String[] REQUIRED_PERMISSIONS =
            new String[]{
                    Manifest.permission.BLUETOOTH,
                    Manifest.permission.BLUETOOTH_ADMIN,
                    Manifest.permission.ACCESS_WIFI_STATE,
                    Manifest.permission.CHANGE_WIFI_STATE,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                    Manifest.permission.ACCESS_FINE_LOCATION,
            };

    private static final int REQUEST_CODE_REQUIRED_PERMISSIONS = 1;


    // Our handle to Nearby Connections
    private ConnectionsClient connectionsClient;

    // Our randomly generated name
    private final String codeName = CodenameGenerator.generate();

    private Network network;
    private Button sendMessageButton;

    private TextView numConnectedText;
    private TextView deviceNameText;
    private TextView lastMessage;
    private EditText sendMessageText;


    // Callbacks for receiving payloads
    private final PayloadCallback payloadCallback =
            new PayloadCallback() {
                @Override
                public void onPayloadReceived(String endpointId, Payload payload) {
                    try {
                        Object deserialized = SerializationHelper.deserialize(payload.asBytes());
                        if (deserialized instanceof String) {
                            String msg = (String) deserialized;
                            Log.d(TAG, msg);
                            if (msg.startsWith(codeName)) {
                                Log.d(TAG, "onPayloadReceived: CYCLE DETECTED - fixing it!");
                                connectionsClient.disconnectFromEndpoint(endpointId);
                            } else {
                                Log.d(TAG, "onPayloadReceived: Forwarding message");
                                sendMessage(msg, endpointId);
                            }
                        } else {
                            HashSet<String> ids = (HashSet<String>) deserialized;
                            network.setEndpoints(endpointId, ids);
                            setNumInNetwork();
                        }
                    } catch (IOException | ClassNotFoundException e) {
                        e.printStackTrace();
                    }
                }

                @Override
                public void onPayloadTransferUpdate(String endpointId, PayloadTransferUpdate update) {
                    if (update.getStatus() == Status.SUCCESS) {
                        Log.i(LATENCY, String.format("%d %d", update.getPayloadId(), System.currentTimeMillis()));
                        Log.d(TAG, "Message successfully received.");
                    }
                }
            };

    // Callbacks for finding other devices
    private final EndpointDiscoveryCallback endpointDiscoveryCallback =
            new EndpointDiscoveryCallback() {
                @Override
                public void onEndpointFound(final String endpointId, final DiscoveredEndpointInfo info) {
                    if (!(network.contains(endpointId) || info.getEndpointName().equals(codeName))) {
                        Log.i(TAG, "onEndpointFound: endpoint found, connecting");
                        connectionsClient.requestConnection(codeName, endpointId, connectionLifecycleCallback).addOnFailureListener(new OnFailureListener() {
                            @Override
                            public void onFailure(@NonNull Exception e) {
//                                ConnectionsStatusCodes.STATUS_ENDPOINT_IO_ERROR;
                                Log.d(TAG, "Endpoint failure " + e.getMessage());

                                // request connection again on one of the devices
                                // 8012: STATUS_ENDPOINT_IO_ERROR is the simulatenous connection requst error
                                if (e.getMessage().startsWith("8012") && codeName.compareTo(info.getEndpointName()) < 0) {
                                    Log.d(TAG, "Sending another connection request.");
                                    connectionsClient.requestConnection(codeName, endpointId, connectionLifecycleCallback);
                                }
                            }
                        });
                    } else {
                        Log.d(TAG, "tried to connect to self...");
                    }
                }

                @Override
                public void onEndpointLost(String endpointId) {
                }

            };
    // Callbacks for connections to other devices
    private final ConnectionLifecycleCallback connectionLifecycleCallback =
            new ConnectionLifecycleCallback() {
                @Override
                public void onConnectionInitiated(String endpointId, ConnectionInfo connectionInfo) {
                    if (network.contains(endpointId)) {
                        Log.i(TAG, "onConnectionInitiated: prevented cycle");
                    } else {
                        Log.i(TAG, "onConnectionInitiated: accepting connection");
                        connectionsClient.acceptConnection(endpointId, payloadCallback);
                    }
                }

                @Override
                public void onConnectionResult(String endpointId, ConnectionResolution result) {
                    if (result.getStatus().isSuccess()) {
                        Log.i(TAG, "onConnectionResult: connection successful");

                        try {
                            if (network.contains(endpointId)) {
                                Log.i(TAG, "onConnectionResult: prevented cycle");
                                connectionsClient.disconnectFromEndpoint(endpointId);
                            } else if (network.addNode(endpointId)) {
                                setNumInNetwork();
                                Log.d(TAG, String.format("onConnectionResult: %s added to network", endpointId));
                            } else {
                                Log.d(TAG, String.format("onConnectionResult: could not add %s to network."));
                            }
                        } catch (IOException e) {
                            e.printStackTrace();
                        }


                    } else {
                        Log.i(TAG, "onConnectionResult: connection failed");
                    }
                }

                @Override
                public void onDisconnected(String endpointId) {
                    Log.i(TAG, "onDisconnected: disconnected from " + endpointId);
                    network.remove(endpointId);
                    setNumInNetwork();
                }
            };

    @Override
    protected void onCreate(@Nullable Bundle bundle) {
        super.onCreate(bundle);
        setContentView(R.layout.activity_main);

        sendMessageButton = findViewById(R.id.sendMessageButton);
        deviceNameText = findViewById(R.id.deviceName);
        numConnectedText = findViewById(R.id.numConnectionsText);
        lastMessage = findViewById(R.id.lastMessage);
        sendMessageText = findViewById(R.id.editTextField);


        deviceNameText.setText(String.format("Device name: %s", codeName));
        int num_bytes = 100 - 7;
        final StringBuilder sb = new StringBuilder(num_bytes);
        for (int i = 0; i < num_bytes; i++) {
            sb.append(".");
        }
        final String msg = sb.toString();
        sendMessageButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                try {
                    sendMessage(String.format("%s: %s",codeName, sendMessageText.getText()), "");
//                    if (sendMessageText.getText().toString().equals("Enter Message Here")) {
//                        Log.i(LATENCY, "SWITCH");
//                        sendMessageText.setText("Enter Message Here!");
//                    }
//                    else {
//                        sendMessage(msg, "");
//                    }
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        });
        connectionsClient = Nearby.getConnectionsClient(this);
        network = new Network(codeName, connectionsClient, endpointDiscoveryCallback, connectionLifecycleCallback);
    }

    @Override
    protected void onStart() {
        super.onStart();

        if (!hasPermissions(this, REQUIRED_PERMISSIONS)) {
            requestPermissions(REQUIRED_PERMISSIONS, REQUEST_CODE_REQUIRED_PERMISSIONS);
        }
    }

    @Override
    protected void onStop() {
        connectionsClient.stopAllEndpoints();

        super.onStop();
    }

    /**
     * Returns true if the app was granted all the permissions. Otherwise, returns false.
     */
    private static boolean hasPermissions(Context context, String... permissions) {
        for (String permission : permissions) {
            if (ContextCompat.checkSelfPermission(context, permission)
                    != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    /**
     * Handles user acceptance (or denial) of our permission request.
     */
    @CallSuper
    @Override
    public void onRequestPermissionsResult(
            int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode != REQUEST_CODE_REQUIRED_PERMISSIONS) {
            return;
        }

        for (int grantResult : grantResults) {
            if (grantResult == PackageManager.PERMISSION_DENIED) {
                Toast.makeText(this, R.string.error_missing_permissions, Toast.LENGTH_LONG).show();
                finish();
                return;
            }
        }
        recreate();
    }




    private void setNumInNetwork() {
        int numInNetwork = network.getSize();
        numConnectedText.setText(String.format("Devices in network: %d", numInNetwork));
        Log.d(TAG, String.format("Devices in network: %d", numInNetwork));
    }

    private void sendMessage(String msg, String ignoreId) throws IOException {
        network.sendMessage(msg, ignoreId);
        Log.d(TAG, "setting message board");
        lastMessage.setText(msg);
    }
}
