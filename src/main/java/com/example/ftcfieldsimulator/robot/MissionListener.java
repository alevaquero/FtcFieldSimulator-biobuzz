package com.example.ftcfieldsimulator.robot;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.SocketException;
import java.net.SocketTimeoutException;

/**
 * DRAFT: Robot-side listener for the Mission Script.
 * Port this to: org.firstinspires.ftc.teamcode.util
 */
public class MissionListener {
    private static final int UDP_PORT = 6666;
    private static final String START_MARKER = "MISSION_START";
    private static final String END_MARKER = "MISSION_END";
    
    private static volatile MissionListener instance;
    private StringBuilder scriptBuffer = new StringBuilder();
    private boolean isBuffering = false;
    private String lastReceivedScript = null;
    private boolean hasNewScript = false;

    private MissionListener() {}

    public static MissionListener getInstance() {
        if (instance == null) {
            synchronized (MissionListener.class) {
                if (instance == null) instance = new MissionListener();
            }
        }
        return instance;
    }

    public synchronized boolean hasNewMission() { return hasNewScript; }
    
    public synchronized String getMissionAndReset() {
        hasNewScript = false;
        return lastReceivedScript;
    }

    public void startListening() {
        new Thread(this::runLoop).start();
    }

    private void runLoop() {
        try (DatagramSocket socket = new DatagramSocket(UDP_PORT)) {
            socket.setSoTimeout(1000);
            byte[] buffer = new byte[1024];
            
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                    socket.receive(packet);
                    String msg = new String(packet.getData(), 0, packet.getLength());

                    if (msg.contains(START_MARKER)) {
                        scriptBuffer = new StringBuilder();
                        isBuffering = true;
                    } else if (msg.contains(END_MARKER)) {
                        synchronized (this) {
                            lastReceivedScript = scriptBuffer.toString();
                            hasNewScript = true;
                        }
                        isBuffering = false;
                    } else if (isBuffering) {
                        scriptBuffer.append(msg);
                    }
                } catch (SocketTimeoutException ignored) {
                } catch (IOException e) {
                    e.printStackTrace();
                    break;
                }
            }
        } catch (SocketException e) {
            e.printStackTrace();
        }
    }
}
