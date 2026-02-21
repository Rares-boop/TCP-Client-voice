package com.example.tcpclient;

import android.os.Bundle;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import com.google.android.material.floatingactionbutton.FloatingActionButton;

import javax.crypto.SecretKey;

import chat.NetworkPacket;
import chat.PacketType;

public class CallActivity extends AppCompatActivity {

    private VoiceCallManager voiceManager;
    private int targetUserId;
    private int currentChatId;
    private String serverIp;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_call);

        targetUserId = getIntent().getIntExtra("TARGET_USER_ID", -1);
        currentChatId = getIntent().getIntExtra("CHAT_ID", -1);
        String username = getIntent().getStringExtra("USERNAME");
        serverIp = getIntent().getStringExtra("SERVER_IP");
        int myUserId = getIntent().getIntExtra("MY_USER_ID", -1);

        TextView txtName = findViewById(R.id.txtCallName);
        txtName.setText(username);

        TextView txtStatus = findViewById(R.id.txtCallStatus);
        txtStatus.setText("Connecting...");

        FloatingActionButton btnEndCall = findViewById(R.id.btnEndCall);
        btnEndCall.setOnClickListener(v -> hangUp());

        initVoiceCall(myUserId);
    }

    @Override
    protected void onResume() {
        super.onResume();
        com.example.tcpclient.TcpConnection.setPacketListener(this::handlePacketOnUI);
    }

    @Override
    protected void onPause() {
        super.onPause();
        com.example.tcpclient.TcpConnection.setPacketListener(null);
    }

    private void handlePacketOnUI(chat.NetworkPacket packet) {
        runOnUiThread(() -> {
            if (packet.getType() == chat.PacketType.CALL_END) {
                android.widget.Toast.makeText(this, "Apel terminat de partener.", android.widget.Toast.LENGTH_SHORT).show();
                closeCallScreen();
            }
            else if (packet.getType() == chat.PacketType.CALL_DENY) {
                android.widget.Toast.makeText(this, "Apel respins (Ocupat).", android.widget.Toast.LENGTH_SHORT).show();
                closeCallScreen();
            }
            else if (packet.getType() == chat.PacketType.CALL_ACCEPT) {
                TextView txtStatus = findViewById(R.id.txtCallStatus);
                txtStatus.setText("Connected");
            }
        });
    }
    private void closeCallScreen() {
        if (voiceManager != null) {
            voiceManager.endCall();
        }
        finish();
    }

    private void initVoiceCall(int myUserId) {
        ClientKeyManager keyManager = new ClientKeyManager(this);
        SecretKey sessionKey = keyManager.getKey(currentChatId);

        if (sessionKey != null) {
            voiceManager = new VoiceCallManager(this, serverIp, myUserId);
            voiceManager.startCall(targetUserId, sessionKey);

            TextView txtStatus = findViewById(R.id.txtCallStatus);
            txtStatus.setText("Connected (Encrypted)");
        } else {
            hangUp();
        }
    }

    private void hangUp() {
        com.example.tcpclient.TcpConnection.sendPacket(new chat.NetworkPacket(chat.PacketType.CALL_END, com.example.tcpclient.TcpConnection.getCurrentUserId(), targetUserId));
        closeCallScreen();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (voiceManager != null) {
            voiceManager.endCall();
        }
    }
}
