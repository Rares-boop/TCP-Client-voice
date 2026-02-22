package com.example.tcpclient.network;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.util.Log;

import androidx.core.content.ContextCompat;

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
    private static final int CHANNEL_CONFIG_IN = AudioFormat.CHANNEL_IN_MONO;
    private static final int CHANNEL_CONFIG_OUT = AudioFormat.CHANNEL_OUT_MONO;
    private static final int AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT;
    private boolean isCallActive = false;
    private final String serverIp;
    private final int myUserId;
    private int targetUserId;
    private SecretKey ramSessionKey;
    private DatagramSocket udpSocket;
    private final Context context;
    private static final String TAG = "VoiceCallManager";

    public VoiceCallManager(Context context, String serverIp, int myUserId) {
        this.context = context;
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
                Log.d(TAG, "Starting UDP call to " + serverIp + ":" + SERVER_PORT);
                sendHolePunch();
                startSending();
                startReceiving();
            } catch (Exception e) {
                Log.e(TAG, "Error starting call: " + e.getMessage());
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
        Log.d(TAG, "Call ended.");
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
        } catch (Exception e) { Log.e(TAG, "Hole punch failed: " + e.getMessage()); }
    }

    private void startSending() {
        Thread sendThread = new Thread(() -> {
            int minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG_IN, AUDIO_FORMAT);
            byte[] audioBuffer = new byte[640];

            AudioRecord recorder = null;

            try {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    Log.e(TAG, "Microphone permission NOT GRANTED!");
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
                Log.e(TAG, "Microphone recording error: " + e.getMessage());
            } finally {
                if (recorder != null) {
                    try { recorder.stop(); recorder.release(); } catch (Exception ignored) {}
                }
            }
        });
        sendThread.start();
    }

    private void startReceiving() {
        Thread receiveThread = new Thread(() -> {
            int minBuf = AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG_OUT, AUDIO_FORMAT);
            AudioTrack speaker = null;
            byte[] receiveBuffer = new byte[4096];

            try {
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
                        speaker.write(pcmAudio, 0, pcmAudio.length);
                    } else {
                        Log.e(TAG, "Decryption failed! Keys might be mismatched.");
                    }
                }
            } catch (Exception e) {
                if (isCallActive) Log.e(TAG, "Speaker error: " + e.getMessage());
            } finally {
                if (speaker != null) {
                    try { speaker.stop(); speaker.release(); } catch (Exception ignored) {}
                }
            }
        });
        receiveThread.start();
    }
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
