import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.Base64;

public class UdpNode {
    private static final int PORT = 9876;
    private static final String DEST_IP = "192.168.118.255";

    private static final long HEARTBEAT_INTERVAL = 5000;
    private static final long DEVICE_TIMEOUT     = 10000;
    private static final long CLEANUP_INTERVAL   = 2000;
    private static final long RESEND_INTERVAL    = 2000;

    private final String deviceName;
    private DatagramSocket socket;
    private ScheduledExecutorService scheduler;

    private final Map<String, DeviceInfo> activeDevices = new HashMap<>();
    private final Map<String, PendingMessage> pendingMessages = new ConcurrentHashMap<>();
    private static long messageCounter = 0;

    private final Set<String> arquivosFinalizados = ConcurrentHashMap.newKeySet();

    public Set<String> getArquivosFinalizados() {
        return arquivosFinalizados;
    }

    public UdpNode(String deviceName) {
        this.deviceName = deviceName;
    }

    public void start() throws IOException {
        socket = new DatagramSocket(PORT);
        socket.setBroadcast(true);
        scheduler = Executors.newScheduledThreadPool(4);

        Thread listenerThread = new Thread(this::listenLoop, "ListenThread");
        listenerThread.start();

        scheduler.scheduleAtFixedRate(this::sendHeartbeat, 0, HEARTBEAT_INTERVAL, TimeUnit.MILLISECONDS);
        scheduler.scheduleAtFixedRate(this::cleanupInactiveDevices, 0, CLEANUP_INTERVAL, TimeUnit.MILLISECONDS);
        scheduler.scheduleAtFixedRate(this::resendPendingMessages, 0, RESEND_INTERVAL, TimeUnit.MILLISECONDS);

        sendHeartbeat();
        consoleLoop();
    }

    private void listenLoop() {
        byte[] buf = new byte[8192];
        while (true) {
            try {
                DatagramPacket packet = new DatagramPacket(buf, buf.length);
                socket.receive(packet);

                String received = new String(packet.getData(), 0, packet.getLength(), StandardCharsets.UTF_8);
                InetAddress senderAddress = packet.getAddress();
                int senderPort = packet.getPort();

                MessageHandler.handleMessage(received, senderAddress, senderPort, this);

            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    private void sendHeartbeat() {
        String message = "HEARTBEAT " + deviceName;
        sendUdp(message, DEST_IP, PORT);
    }

    private synchronized void cleanupInactiveDevices() {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<String, DeviceInfo>> it = activeDevices.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, DeviceInfo> entry = it.next();
            DeviceInfo info = entry.getValue();
            if ((now - info.getLastHeartbeat()) > DEVICE_TIMEOUT) {
                System.out.println(">>> [INFO] Dispositivo inativo removido: " + info.getName());
                it.remove();
            }
        }
    }

    private void resendPendingMessages() {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<String, PendingMessage>> it = pendingMessages.entrySet().iterator();
        
        while (it.hasNext()) {
            Map.Entry<String, PendingMessage> entry = it.next();
            String messageId = entry.getKey();
            PendingMessage pm = entry.getValue();
            
            // Verifica se este é um chunk de um arquivo já finalizado
            if (messageId.contains("-seq")) {
                String baseId = messageId.substring(0, messageId.indexOf("-seq"));
                if (arquivosFinalizados.contains(baseId)) {
                    it.remove();  // Remove chunks de arquivos já finalizados
                    continue;
                }
            }
            
            // Se o arquivo base está finalizado, remove a mensagem
            if (arquivosFinalizados.contains(messageId)) {
                it.remove();
                continue;
            }
            
            // Reenvia mensagens pendentes que não foram confirmadas após 3 segundos
            if ((now - pm.getLastSent()) > 3000) {
                System.out.println("[RETX] Reenviando ID=" + pm.getId());
                sendUdp(pm.getMessage(), pm.getDestIp(), pm.getDestPort());
                pm.updateLastSent();
            }
        }
    }

    public void sendUdp(String msg, String destIp, int destPort) {
        try {
            byte[] data = msg.getBytes(StandardCharsets.UTF_8);
            DatagramPacket packet = new DatagramPacket(data, data.length,
                                                       InetAddress.getByName(destIp), destPort);
            socket.send(packet);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void consoleLoop() {
        Scanner sc = new Scanner(System.in);
        while (true) {
            System.out.print("> ");
            String line = sc.nextLine().trim();
            if (line.isEmpty()) continue;

            String[] parts = line.split(" ", 2);
            String cmd = parts[0].toLowerCase();

            switch (cmd) {
                case "devices":
                    listDevices();
                    break;

                case "talk":
                    if (parts.length < 2) {
                        System.out.println("Uso: talk <nome> <mensagem>");
                        break;
                    }
                    String[] talkParts = parts[1].split(" ", 2);
                    if (talkParts.length < 2) {
                        System.out.println("Uso: talk <nome> <mensagem>");
                        break;
                    }
                    sendTalk(talkParts[0], talkParts[1]);
                    break;

                case "sendfile":
                    if (parts.length < 2) {
                        System.out.println("Uso: sendfile <nome> <caminho-arquivo>");
                        break;
                    }
                    String[] fileParts = parts[1].split(" ", 2);
                    if (fileParts.length < 2) {
                        System.out.println("Uso: sendfile <nome> <caminho-arquivo>");
                        break;
                    }
                    sendFile(fileParts[0], fileParts[1]);
                    break;

                default:
                    System.out.println("Comando não reconhecido: " + cmd);
            }
        }
    }

    private synchronized void listDevices() {
        System.out.println("=== Dispositivos Ativos ===");
        long now = System.currentTimeMillis();
        for (DeviceInfo info : activeDevices.values()) {
            long diff = now - info.getLastHeartbeat();
            System.out.println("* " + info.getName() + " - " + info.getIp() + ":" + info.getPort()
                               + " (último heartbeat há " + diff + " ms)");
        }
        System.out.println("===========================");
    }

    private void sendTalk(String targetName, String content) {
        DeviceInfo info = activeDevices.get(targetName);
        if (info == null) {
            System.out.println("[ERRO] Dispositivo não encontrado: " + targetName);
            return;
        }
        String id = generateMessageId();
        String msg = "TALK " + id + " " + content;
        pendingMessages.put(id, new PendingMessage(id, msg, info.getIp(), info.getPort()));
        sendUdp(msg, info.getIp(), info.getPort());
        System.out.println(">>> [TALK enviado] ID=" + id + " para " + targetName);
    }

    private void sendFile(String targetName, String filePath) {
        DeviceInfo info = activeDevices.get(targetName);
        if (info == null) {
            System.out.println("[ERRO] Dispositivo não encontrado: " + targetName);
            return;
        }
        File f = new File(filePath);
        if (!f.exists()) {
            System.out.println("[ERRO] Arquivo não encontrado: " + filePath);
            return;
        }
        String id = generateMessageId();
        long fileSize = f.length();
        String fileName = f.getName();
        String fileMsg = "FILE " + id + " " + fileName + " " + fileSize;
        pendingMessages.put(id, new PendingMessage(id, fileMsg, info.getIp(), info.getPort()));
        sendUdp(fileMsg, info.getIp(), info.getPort());
        System.out.println(">>> [FILE enviado] ID=" + id + " Arquivo=" + fileName + " Tamanho=" + fileSize);

        try (FileInputStream fis = new FileInputStream(f)) {
            byte[] buffer = new byte[1024];
            int read;
            int seq = 0;
            while ((read = fis.read(buffer)) != -1) {
                seq++;
                byte[] chunk = Arrays.copyOf(buffer, read);
                String base64 = Base64.getEncoder().encodeToString(chunk);
                String chunkMsg = "CHUNK " + id + " " + seq + " " + base64;
                String chunkId = id + "-seq" + seq;
                pendingMessages.put(chunkId, new PendingMessage(chunkId, chunkMsg, info.getIp(), info.getPort()));
                sendUdp(chunkMsg, info.getIp(), info.getPort());
                System.out.println("... enviado CHUNK seq=" + seq + " (" + read + " bytes)");
            }
            String fileHash = FileUtils.calculateMD5(f);
            String endMsg = "END " + id + " " + fileHash;
            pendingMessages.put(id, new PendingMessage(id, endMsg, info.getIp(), info.getPort()));
            sendUdp(endMsg, info.getIp(), info.getPort());
            System.out.println(">>> [END enviado] ID=" + id + " hash=" + fileHash);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static synchronized String generateMessageId() {
        messageCounter++;
        return "msg" + messageCounter;
    }

    public Map<String, DeviceInfo> getActiveDevices() {
        return activeDevices;
    }

    public Map<String, PendingMessage> getPendingMessages() {
        return pendingMessages;
    }

    public String getDeviceName() {
        return deviceName;
    }
} 
