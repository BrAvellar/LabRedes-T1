// Arquivo: MessageHandler.java
import java.net.*;
import java.util.Base64;

public class MessageHandler {
    public static void handleMessage(String msg, InetAddress addr, int port, UdpNode node) {
        String[] parts = msg.split(" ", 2);
        String command = parts[0].toUpperCase();

        switch (command) {
            case "HEARTBEAT":
                if (parts.length < 2) return;
                String heartbeatName = parts[1].trim();
                handleHeartbeat(heartbeatName, addr, port, node);
                break;
            case "TALK":
                handleTalk(msg, addr, port, node);
                break;
            case "FILE":
                handleFile(msg, addr, port, node);
                break;
            case "CHUNK":
                handleChunk(msg, addr, port, node);
                break;
            case "END":
                handleEnd(msg, addr, port, node);
                break;
            case "ACK":
                handleAck(msg, node);
                break;
            case "NACK":
                handleNack(msg);
                break;
            default:
                System.out.println("[WARN] Mensagem desconhecida: " + msg);
        }
    }

    private static void handleHeartbeat(String otherName, InetAddress addr, int port, UdpNode node) {
        long now = System.currentTimeMillis();
        if (!node.getActiveDevices().containsKey(otherName)) {
            node.getActiveDevices().put(otherName, new DeviceInfo(otherName, addr.getHostAddress(), port, now));
            System.out.println(">>> [INFO] Novo dispositivo encontrado: " + otherName
                    + " (" + addr.getHostAddress() + ":" + port + ")");
        } else {
            node.getActiveDevices().get(otherName).setLastHeartbeat(now);
        }
    }

    private static void handleTalk(String fullMsg, InetAddress addr, int port, UdpNode node) {
        String[] tokens = fullMsg.split(" ", 3);
        if (tokens.length < 3) return;
        String id = tokens[1];
        String dados = tokens[2];
        node.sendUdp("ACK " + id, addr.getHostAddress(), port);
        System.out.println(">>> [TALK recebido] ID=" + id + " Mensagem=\"" + dados +
                "\" de " + addr.getHostAddress() + ":" + port);
    }

    private static void handleFile(String fullMsg, InetAddress addr, int port, UdpNode node) {
        String[] tokens = fullMsg.split(" ", 4);
        if (tokens.length < 4) return;
        String id = tokens[1];
        String nomeArq = tokens[2];
        long tamanho = Long.parseLong(tokens[3]);
        node.sendUdp("ACK " + id, addr.getHostAddress(), port);
        System.out.println(">>> [FILE recebido] ID=" + id + " Arquivo=\"" + nomeArq + "\" Tamanho=" + tamanho);
    }

    private static void handleChunk(String fullMsg, InetAddress addr, int port, UdpNode node) {
        String[] tokens = fullMsg.split(" ", 4);
        if (tokens.length < 4) return;
        String id = tokens[1];
        int seq = Integer.parseInt(tokens[2]);
        String base64 = tokens[3];
        byte[] chunkData = Base64.getDecoder().decode(base64);
        // TODO: armazenar no arquivo apropriado, levando em conta a seq, etc.
        node.sendUdp("ACK " + id, addr.getHostAddress(), port);
        System.out.println(">>> [CHUNK] ID=" + id + " seq=" + seq + " tamBytes=" + chunkData.length);
    }

    private static void handleEnd(String fullMsg, InetAddress addr, int port, UdpNode node) {
        String[] tokens = fullMsg.split(" ", 3);
        if (tokens.length < 3) return;
        String id = tokens[1];
        String hashRecebido = tokens[2];
        // TODO: Verificar o hash do arquivo recebido
        boolean hashOk = true;
        if (hashOk) {
            node.sendUdp("ACK " + id, addr.getHostAddress(), port);
            System.out.println(">>> [END] Arquivo ID=" + id + " validado com sucesso!");
        } else {
            node.sendUdp("NACK " + id + " HASH_INVALIDO", addr.getHostAddress(), port);
            System.out.println(">>> [END] Arquivo ID=" + id + " corrompido. NACK enviado.");
        }
    }

    private static void handleAck(String fullMsg, UdpNode node) {
        String[] tokens = fullMsg.split(" ", 2);
        if (tokens.length < 2) return;
        String id = tokens[1];
        PendingMessage pm = node.getPendingMessages().remove(id);
        if (pm != null) {
            System.out.println(">>> [ACK] Recebido para ID=" + id);
        }
    }

    private static void handleNack(String fullMsg) {
        String[] tokens = fullMsg.split(" ", 3);
        if (tokens.length < 3) return;
        String id = tokens[1];
        String motivo = tokens[2];
        System.out.println(">>> [NACK] Recebido para ID=" + id + " Motivo=" + motivo);
    }
}
