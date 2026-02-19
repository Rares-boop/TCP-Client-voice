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

        // 1. Luam datele din Intent (trimise din ConversationActivity)
        targetUserId = getIntent().getIntExtra("TARGET_USER_ID", -1);
        currentChatId = getIntent().getIntExtra("CHAT_ID", -1);
        String username = getIntent().getStringExtra("USERNAME");
        serverIp = getIntent().getStringExtra("SERVER_IP");
        int myUserId = getIntent().getIntExtra("MY_USER_ID", -1);

        // Setup UI
        TextView txtName = findViewById(R.id.txtCallName);
        txtName.setText(username);

        TextView txtStatus = findViewById(R.id.txtCallStatus);
        txtStatus.setText("Connecting...");

        FloatingActionButton btnEndCall = findViewById(R.id.btnEndCall);
        btnEndCall.setOnClickListener(v -> hangUp());

        // 2. Initializam Cheile si Managerul
        initVoiceCall(myUserId);
    }

    private void initVoiceCall(int myUserId) {
        // AICI E TOATA SMECHERIA "HARDWARE BACKED":
        // Citim cheia o singura data cand se creeaza activitatea.
        ClientKeyManager keyManager = new ClientKeyManager(this);
        SecretKey sessionKey = keyManager.getKey(currentChatId);

        if (sessionKey != null) {
            // Initializam managerul
            voiceManager = new VoiceCallManager(this, serverIp, myUserId);

            // Pornim apelul (UDP + Hole Punching)
            voiceManager.startCall(targetUserId, sessionKey);

            TextView txtStatus = findViewById(R.id.txtCallStatus);
            txtStatus.setText("Connected (Encrypted)");
        } else {
            // Daca nu avem cheie, inchidem
            hangUp();
        }
    }

    private void hangUp() {
        if (voiceManager != null) {
            voiceManager.endCall();
        }

        TcpConnection.sendPacket(new NetworkPacket(PacketType.CALL_END, TcpConnection.getCurrentUserId(),
                targetUserId));
        // Aici ai putea trimite si un pachet TCP de CALL_END daca vrei sa fii elegant
        finish(); // Inchide activitatea si ne intoarce in chat
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // Siguranta: daca userul da back sau inchide fortat, oprim threadurile
        if (voiceManager != null) {
            voiceManager.endCall();
        }
    }
}
