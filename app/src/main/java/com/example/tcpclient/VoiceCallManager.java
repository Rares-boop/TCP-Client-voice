package com.example.tcpclient;

import android.Manifest;
import android.content.Context; // <--- IMPORTANT
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.util.Log;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat; // <--- IMPORTANT

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.security.SecureRandom;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

public class VoiceCallManager {
    private static final int SERVER_PORT = 15556;
    private static final int SAMPLE_RATE = 16000;
    private static final int CHANNEL_CONFIG_IN = AudioFormat.CHANNEL_IN_MONO;   // Pt Microfon
    private static final int CHANNEL_CONFIG_OUT = AudioFormat.CHANNEL_OUT_MONO;
    private static final int AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT;

    private boolean isCallActive = false;
    private String serverIp;
    private int myUserId;
    private int targetUserId;
    private SecretKey ramSessionKey;
    private DatagramSocket udpSocket;
    private Thread sendThread;
    private Thread receiveThread;

    // --- MODIFICARE 1: Avem nevoie de Context pentru permisiuni ---
    private Context context;

    public VoiceCallManager(Context context, String serverIp, int myUserId) {
        this.context = context; // Il salvam
        this.serverIp = serverIp;
        this.myUserId = myUserId;
    }

    public void startCall(int targetUserId, SecretKey sessionKey) {
        if (isCallActive) return;

        this.targetUserId = targetUserId;
        this.ramSessionKey = sessionKey;
        this.isCallActive = true;

        new Thread(() -> {
            try {
                udpSocket = new DatagramSocket();
                Log.d("VoiceCall", "🚀 Pornire apel UDP catre " + serverIp + ":" + SERVER_PORT);
                sendHolePunch();
                startSending();
                startReceiving();
            } catch (Exception e) {
                e.printStackTrace();
                Log.e("VoiceCall", "Eroare la pornire apel: " + e.getMessage());
                endCall();
            }
        }).start();
    }

    public void endCall() {
        if (!isCallActive) return;
        isCallActive = false;
        if (udpSocket != null && !udpSocket.isClosed()) {
            udpSocket.close();
            udpSocket = null;
        }
        ramSessionKey = null;
        Log.d("VoiceCall", "🛑 Apel terminat.");
    }

    private void sendHolePunch() {
        try {
            ByteBuffer buffer = ByteBuffer.allocate(8);
            buffer.putInt(myUserId);
            buffer.putInt(targetUserId);
            byte[] data = buffer.array();
            InetAddress serverAddress = InetAddress.getByName(serverIp);
            DatagramPacket packet = new DatagramPacket(data, data.length, serverAddress, SERVER_PORT);
            for (int i = 0; i < 5; i++) {
                if (udpSocket != null) udpSocket.send(packet);
                Thread.sleep(50);
            }
        } catch (Exception e) { e.printStackTrace(); }
    }

    private void startSending() {
        sendThread = new Thread(() -> {
            int minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG_IN, AUDIO_FORMAT);
            // Buffer mic (20-40ms) pentru latenta mica
            byte[] audioBuffer = new byte[640];

            AudioRecord recorder = null;

            try {
                // --- MODIFICARE 2: Verificam permisiunea folosind Contextul ---
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    Log.e("VoiceCall", "❌ NU AI PERMISIUNE DE MICROFON!");
                    return;
                }

                recorder = new AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE, CHANNEL_CONFIG_IN, AUDIO_FORMAT, minBuf);
                recorder.startRecording();

                InetAddress serverAddress = InetAddress.getByName(serverIp);

                while (isCallActive) {
                    int read = recorder.read(audioBuffer, 0, audioBuffer.length);
                    if (read > 0) {
                        byte[] encryptedAudio = encryptUDP(ramSessionKey, audioBuffer);
                        if (encryptedAudio != null) {
                            ByteBuffer packetBuf = ByteBuffer.allocate(8 + encryptedAudio.length);
                            packetBuf.putInt(myUserId);
                            packetBuf.putInt(targetUserId);
                            packetBuf.put(encryptedAudio);
                            byte[] packetData = packetBuf.array();
                            DatagramPacket p = new DatagramPacket(packetData, packetData.length, serverAddress, SERVER_PORT);
                            if (udpSocket != null) udpSocket.send(p);
                        }
                    }
                }
            } catch (Exception e) {
                Log.e("VoiceCall", "Eroare Microfon: " + e.getMessage());
            } finally {
                if (recorder != null) {
                    try { recorder.stop(); recorder.release(); } catch (Exception e) {}
                }
            }
        });
        sendThread.start();
    }

    private void startReceiving() {
        receiveThread = new Thread(() -> {
            int minBuf = AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG_OUT, AUDIO_FORMAT);
            AudioTrack speaker = null;
            byte[] receiveBuffer = new byte[4096];

            try {
//                speaker = new AudioTrack(AudioManager.STREAM_VOICE_CALL, SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT, minBuf, AudioTrack.MODE_STREAM);
                speaker = new AudioTrack(AudioManager.STREAM_MUSIC, SAMPLE_RATE, CHANNEL_CONFIG_OUT, AUDIO_FORMAT, minBuf, AudioTrack.MODE_STREAM);
                speaker.play();

                while (isCallActive) {
                    if (udpSocket == null || udpSocket.isClosed()) break;
                    DatagramPacket packet = new DatagramPacket(receiveBuffer, receiveBuffer.length);
                    udpSocket.receive(packet);

                    if (packet.getLength() <= 8) continue;

                    byte[] encryptedData = new byte[packet.getLength() - 8];
                    System.arraycopy(packet.getData(), 8, encryptedData, 0, encryptedData.length);
                    byte[] pcmAudio = decryptUDP(ramSessionKey, encryptedData);

                    if (pcmAudio != null) {
                        // Log.d("VoiceCall", "🔊 Primit sunet: " + pcmAudio.length + " bytes"); // Decomenteaza daca vrei spam
                        speaker.write(pcmAudio, 0, pcmAudio.length);
                    } else {
                        Log.e("VoiceCall", "❌ Decriptare Esuata! Cheile nu se potrivesc.");
                    }
                }
            } catch (Exception e) {
                if (isCallActive) Log.e("VoiceCall", "Eroare Difuzor: " + e.getMessage());
            } finally {
                if (speaker != null) {
                    try { speaker.stop(); speaker.release(); } catch (Exception e) {}
                }
            }
        });
        receiveThread.start();
    }

    // --- METODELE TALE DE CRIPTARE ---
    public static byte[] encryptUDP(SecretKey key, byte[] audioData) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            byte[] iv = new byte[12];
            new SecureRandom().nextBytes(iv);
            GCMParameterSpec spec = new GCMParameterSpec(128, iv);
            cipher.init(Cipher.ENCRYPT_MODE, key, spec);
            byte[] cipherText = cipher.doFinal(audioData);
            ByteBuffer byteBuffer = ByteBuffer.allocate(iv.length + cipherText.length);
            byteBuffer.put(iv);
            byteBuffer.put(cipherText);
            return byteBuffer.array();
        } catch (Exception e) { return null; }
    }

    public static byte[] decryptUDP(SecretKey key, byte[] encryptedPacket) {
        try {
            if (encryptedPacket.length < 12) return null;
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            GCMParameterSpec spec = new GCMParameterSpec(128, encryptedPacket, 0, 12);
            cipher.init(Cipher.DECRYPT_MODE, key, spec);
            return cipher.doFinal(encryptedPacket, 12, encryptedPacket.length - 12);
        } catch (Exception e) { return null; }
    }
}
