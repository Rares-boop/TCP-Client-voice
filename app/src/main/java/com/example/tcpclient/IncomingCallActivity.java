package com.example.tcpclient;

import android.content.Context;
import android.content.Intent;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Vibrator;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;

import chat.NetworkPacket;
import chat.PacketType;

public class IncomingCallActivity extends AppCompatActivity {

    private Ringtone ringtone;
    private Vibrator vibrator;

    // Date primite prin Intent de la TcpClient
    private int callerId;
    private String callerName;
    private int chatId; // Avem nevoie de asta ca sa stim ce cheie folosim
    private String serverIp;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_incoming_call);

        // 1. Luam datele
        callerId = getIntent().getIntExtra("CALLER_ID", -1);
        callerName = getIntent().getStringExtra("CALLER_NAME");
        chatId = getIntent().getIntExtra("CHAT_ID", -1);
        serverIp = getIntent().getStringExtra("SERVER_IP"); // Important pentru CallActivity

        // 2. Setam UI
        TextView txtName = findViewById(R.id.txtCallerName);
        txtName.setText(callerName != null ? callerName : "Unknown Caller");

        // 3. Pornim SONERIA si VIBRATIA 🔔📳
        startRinging();

        // 4. Butoane
        findViewById(R.id.btnAnswer).setOnClickListener(v -> answerCall());
        findViewById(R.id.btnDecline).setOnClickListener(v -> declineCall());
    }

    private void startRinging() {
        try {
            // Sonerie default
            Uri notification = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE);
            ringtone = RingtoneManager.getRingtone(getApplicationContext(), notification);
            ringtone.play();

            // Vibratii
            vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            if (vibrator != null) {
                // Vibreaza: 0s pauza, 1s vibreaza, 1s pauza... (Repeta de la index 0)
                long[] pattern = {0, 1000, 1000};
                vibrator.vibrate(pattern, 0);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void stopRinging() {
        if (ringtone != null && ringtone.isPlaying()) {
            ringtone.stop();
        }
        if (vibrator != null) {
            vibrator.cancel();
        }
    }

    // --- RASPUNS (VERDE) ---
    private void answerCall() {
        stopRinging();

        // 1. Trimitem pachet TCP: CALL_ACCEPT
        // (Asta ii zice serverului/celuilalt telefon ca am raspuns)
        // TcpClient.getInstance().sendPacket(new NetworkPacket(PacketType.CALL_ACCEPT, myId, callerId));
        // NOTA: Implementeaza trimiterea in TcpClient sau aici daca ai acces static.
        sendTcpResponse(PacketType.CALL_ACCEPT);

        // 2. Deschidem CallActivity (Ecranul de vorbit)
        Intent intent = new Intent(this, CallActivity.class);
        intent.putExtra("TARGET_USER_ID", callerId);
        intent.putExtra("CHAT_ID", chatId);
        intent.putExtra("USERNAME", callerName);
        intent.putExtra("SERVER_IP", serverIp);
        // intent.putExtra("MY_USER_ID", ...); // Daca iti trebuie
        intent.putExtra("MY_USER_ID", com.example.tcpclient.TcpConnection.getCurrentUserId());

        startActivity(intent);
        finish(); // Inchidem ecranul de Incoming
    }

    // --- RESPINS (ROSU) ---
    private void declineCall() {
        stopRinging();

        // 1. Trimitem pachet TCP: CALL_DENY
        sendTcpResponse(PacketType.CALL_DENY);

        // 2. Inchidem activitatea si ne intoarcem unde eram
        finish();
    }

    private void sendTcpResponse(PacketType type) {
        // Trimitem pachet TCP catre cel care ne suna (callerId)
        // Payload poate fi ID-ul nostru sau un mesaj simplu "OK"
        com.example.tcpclient.TcpConnection.sendPacket(
                new NetworkPacket(type, com.example.tcpclient.TcpConnection.getCurrentUserId(), callerId)
        );
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopRinging(); // Siguranta: sa nu sune la infinit daca iesi din aplicatie
    }
}
