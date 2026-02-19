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
        btnEndCall.setOnClickListener(v -> closeCallScreen());

        // 2. Initializam Cheile si Managerul
        initVoiceCall(myUserId);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Spunem aplicatiei: "Cat timp ecranul de apel e in fata, EU ascult pachetele!"
        com.example.tcpclient.TcpConnection.setPacketListener(this::handlePacketOnUI);
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Cand se inchide ecranul, oprim ascultarea aici (o va prelua chat-ul inapoi)
        com.example.tcpclient.TcpConnection.setPacketListener(null);
    }

    private void handlePacketOnUI(chat.NetworkPacket packet) {
        runOnUiThread(() -> {
            // DACA CELALALT A INCHIS APELUL (Call End)
            if (packet.getType() == chat.PacketType.CALL_END) {
                android.widget.Toast.makeText(this, "Apel terminat de partener.", android.widget.Toast.LENGTH_SHORT).show();
                closeCallScreen();
            }
            // DACA CELALALT NE-A DAT REJECT (Call Deny)
            else if (packet.getType() == chat.PacketType.CALL_DENY) {
                android.widget.Toast.makeText(this, "Apel respins (Ocupat).", android.widget.Toast.LENGTH_SHORT).show();
                closeCallScreen();
            }
            // (Optional) Daca vrei sa schimbi textul cand raspunde
            else if (packet.getType() == chat.PacketType.CALL_ACCEPT) {
                TextView txtStatus = findViewById(R.id.txtCallStatus);
                txtStatus.setText("Connected");
            }
        });
    }

    // Metoda de curatare pe care o apelam ca sa iesim frumos
    private void closeCallScreen() {
        if (voiceManager != null) {
            voiceManager.endCall();
        }
        finish(); // Iese din ecran
    }

    private void initVoiceCall(int myUserId) {
        // AICI E TOATA SMECHERIA "HARDWARE BACKED":
        // Citim cheia o singura data cand se creeaza activitatea.
        ClientKeyManager keyManager = new ClientKeyManager(this);
        SecretKey sessionKey = keyManager.getKey(currentChatId);

        if (sessionKey != null) {
            // Initializam managerul
            voiceManager = new VoiceCallManager(this, serverIp, myUserId);

            voiceManager.startCall(targetUserId, sessionKey);

            TextView txtStatus = findViewById(R.id.txtCallStatus);
            txtStatus.setText("Connected (Encrypted)");
        } else {
            // Daca nu avem cheie, inchidem
            hangUp();
        }
    }

    private void hangUp() {
        // Tu ii zici serverului ca ai inchis
        com.example.tcpclient.TcpConnection.sendPacket(new chat.NetworkPacket(chat.PacketType.CALL_END, com.example.tcpclient.TcpConnection.getCurrentUserId(), targetUserId));

        // Apoi inchizi ecranul tau
        closeCallScreen();
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
