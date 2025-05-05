import java.net.*;
import java.util.Base64;
import java.io.*;
import java.util.HashMap;
import java.util.Map;

public class MessageHandler {
    // Estrutura para rastrear arquivos sendo recebidos
    private static final Map<String, FileInfo> receivingFiles = new HashMap<>();
    
    // Classe interna para rastrear informações de arquivos sendo recebidos
    private static class FileInfo {
        String fileName;
        long size;
        RandomAccessFile file;
        Map<Integer, Boolean> receivedChunks = new HashMap<>();
        
        public FileInfo(String fileName, long size) throws IOException {
            this.fileName = fileName;
            this.size = size;
            this.file = new RandomAccessFile("received_" + fileName, "rw");
            file.setLength(size);
        }
    }

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
        
        try {
            // Inicializa a estrutura para receber os chunks
            FileInfo fileInfo = new FileInfo(nomeArq, tamanho);
            receivingFiles.put(id, fileInfo);
            
            node.sendUdp("ACK " + id, addr.getHostAddress(), port);
            System.out.println(">>> [FILE recebido] ID=" + id + " Arquivo=\"" + nomeArq + "\" Tamanho=" + tamanho);
        } catch (IOException e) {
            System.out.println(">>> [ERRO] Falha ao inicializar arquivo: " + e.getMessage());
            node.sendUdp("NACK " + id + " FALHA_INICIALIZACAO", addr.getHostAddress(), port);
        }
    }

    private static void handleChunk(String fullMsg, InetAddress addr, int port, UdpNode node) {
        String[] tokens = fullMsg.split(" ", 4);
        if (tokens.length < 4) return;
        String id = tokens[1];
        int seq = Integer.parseInt(tokens[2]);
        String base64 = tokens[3];
        byte[] chunkData = Base64.getDecoder().decode(base64);

        if (node.getArquivosFinalizados().contains(id)) {
            System.out.println(">>> [INFO] CHUNK ignorado pois transferência já finalizada: ID=" + id + " seq=" + seq);
            // Envia ACK mesmo assim para evitar retransmissões
            node.sendUdp("ACK " + id, addr.getHostAddress(), port);
            return;
        }
        
        FileInfo fileInfo = receivingFiles.get(id);
        if (fileInfo == null) {
            System.out.println(">>> [ERRO] Recebido CHUNK para transferência desconhecida: " + id);
            node.sendUdp("NACK " + id + " TRANSFERENCIA_NAO_INICIADA", addr.getHostAddress(), port);
            return;
        }
        
        // Verifica se este chunk já foi recebido (para evitar duplicatas)
        if (fileInfo.receivedChunks.containsKey(seq) && fileInfo.receivedChunks.get(seq)) {
            System.out.println(">>> [INFO] CHUNK duplicado ignorado: ID=" + id + " seq=" + seq);
            // Confirma mesmo assim para que o remetente possa prosseguir
            node.sendUdp("ACK " + id, addr.getHostAddress(), port);
            return;
        }
        
        try {
            // Calcula a posição no arquivo baseado no número de sequência
            long position = (seq - 1) * 1024L; // Assume chunks de 1KB
            if (position >= fileInfo.size) {
                System.out.println(">>> [ERRO] Posição de chunk inválida: " + position + " (tamanho do arquivo: " + fileInfo.size + ")");
                return;
            }
            
            // Escreve os dados no arquivo na posição correta
            fileInfo.file.seek(position);
            fileInfo.file.write(chunkData);
            
            // Marca este chunk como recebido
            fileInfo.receivedChunks.put(seq, true);
            
            node.sendUdp("ACK " + id, addr.getHostAddress(), port);
            System.out.println(">>> [CHUNK] ID=" + id + " seq=" + seq + " tamBytes=" + chunkData.length + " armazenado");
        } catch (IOException e) {
            System.out.println(">>> [ERRO] Falha ao armazenar chunk: " + e.getMessage());
            node.sendUdp("NACK " + id + " FALHA_IO", addr.getHostAddress(), port);
        }
    }

    private static void handleEnd(String fullMsg, InetAddress addr, int port, UdpNode node) {
        String[] tokens = fullMsg.split(" ", 3);
        if (tokens.length < 3) return;
        String id = tokens[1];
        String hashRecebido = tokens[2];
        
        FileInfo fileInfo = receivingFiles.get(id);
        if (fileInfo == null) {
            System.out.println(">>> [ERRO] Recebido END para transferência desconhecida: " + id);
            node.sendUdp("NACK " + id + " TRANSFERENCIA_NAO_INICIADA", addr.getHostAddress(), port);
            return;
        }
        
        try {
            fileInfo.file.close();
            
            // Calcula o hash do arquivo recebido
            File file = new File("received_" + fileInfo.fileName);
            String hashCalculado = FileUtils.calculateMD5(file);
            
            boolean hashOk = hashCalculado.equalsIgnoreCase(hashRecebido);
            
            if (hashOk) {
                node.sendUdp("ACK " + id, addr.getHostAddress(), port);
                System.out.println(">>> [END] Arquivo ID=" + id + " nome=" + fileInfo.fileName + " validado com sucesso!");
                System.out.println("    Hash recebido: " + hashRecebido);
                System.out.println("    Hash calculado: " + hashCalculado);
            } else {
                node.sendUdp("NACK " + id + " HASH_INVALIDO", addr.getHostAddress(), port);
                System.out.println(">>> [END] Arquivo ID=" + id + " corrompido. NACK enviado.");
                System.out.println("    Hash recebido: " + hashRecebido);
                System.out.println("    Hash calculado: " + hashCalculado);
                // Deleta o arquivo corrompido
                file.delete();
            }
            
            node.getArquivosFinalizados().add(id);
            // Remove o arquivo da lista de transferências em andamento
            receivingFiles.remove(id);
            
        } catch (Exception e) {
            System.out.println(">>> [ERRO] Falha ao processar fim da transferência: " + e.getMessage());
            node.sendUdp("NACK " + id + " ERRO_PROCESSAMENTO", addr.getHostAddress(), port);
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
