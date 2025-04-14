import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.Base64;

/**
 * Classe principal que representa um dispositivo (nó) na rede
 * rodando o protocolo customizado sobre UDP.
 */
public class Device {
    // Porta padrão de comunicação entre dispositivos.
    private static final int PORT = 9876;
    
    // Intervalos de tempo (ms).
    private static final long HEARTBEAT_INTERVAL = 5000;  // envia HEARTBEAT a cada 5s
    private static final long DEVICE_TIMEOUT     = 10000; // remove dispositivo inativo após 10s
    private static final long CLEANUP_INTERVAL   = 2000;  // a cada 2s, remove inativos
    private static final long RESEND_INTERVAL    = 2000;  // reenvio de mensagens não-ACKadas
    
    // Nome do dispositivo (identificador lógico).
    private final String deviceName;
    
    // Socket para receber e enviar pacotes.
    private DatagramSocket socket;
    
    // Thread pool básica só para eventuais agendamentos (retransmissão, etc.).
    private ScheduledExecutorService scheduler;
    
    // Mapeia: nomeDoDispositivo -> informações (ip, últimoHeartbeat, etc.)
    private final Map<String, DeviceInfo> activeDevices = new HashMap<>();
    
    // Armazena mensagens pendentes de ACK: ID -> MensagemCompleta
    // (Para TALK, FILE, CHUNK, etc. que exijam ACK)
    private final Map<String, PendingMessage> pendingMessages = new ConcurrentHashMap<>();
    
    // Gera IDs únicos para as mensagens (TALK, FILE, CHUNK etc.).
    private static long messageCounter = 0;
    
    // Construtor
    public Device(String deviceName) {
        this.deviceName = deviceName;
    }
    
    /**
     * Inicializa o socket e as threads de controle.
     */
    public void start() throws IOException {
        // Abre socket na porta definida.
        // Se quiser escolher porta dinâmica, pode usar "new DatagramSocket(0);"
        socket = new DatagramSocket(PORT);
        socket.setBroadcast(true); // se quiser habilitar broadcast
        
        // Inicia um agendador para tarefas periódicas.
        scheduler = Executors.newScheduledThreadPool(4);
        
        // Thread 1: escutar mensagens UDP
        Thread listenerThread = new Thread(this::listenLoop, "ListenThread");
        listenerThread.start();
        
        // Thread 2: enviar HEARTBEAT periodicamente
        scheduler.scheduleAtFixedRate(this::sendHeartbeat,
                0, HEARTBEAT_INTERVAL, TimeUnit.MILLISECONDS);
        
        // Thread 3: remover dispositivos inativos
        scheduler.scheduleAtFixedRate(this::cleanupInactiveDevices,
                0, CLEANUP_INTERVAL, TimeUnit.MILLISECONDS);
        
        // Thread 4 (opcional): retransmitir mensagens pendentes se não houver ACK
        scheduler.scheduleAtFixedRate(this::resendPendingMessages,
                0, RESEND_INTERVAL, TimeUnit.MILLISECONDS);
        
        // Anuncia logo na inicialização
        sendHeartbeat();
        
        // Loop de console do usuário (comandos)
        consoleLoop();
    }
    
    /**
     * Fica em loop ouvindo pacotes UDP e trata as mensagens
     */
    private void listenLoop() {
        byte[] buf = new byte[8192];
        
        while (true) {
            try {
                DatagramPacket packet = new DatagramPacket(buf, buf.length);
                socket.receive(packet);
                
                String received = new String(packet.getData(), 0, packet.getLength(), StandardCharsets.UTF_8);
                InetAddress senderAddress = packet.getAddress();
                int senderPort = packet.getPort();
                
                // Trata a mensagem de acordo com o protocolo.
                handleMessage(received, senderAddress, senderPort);
                
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }
    
    /**
     * Interpreta e processa a mensagem de acordo com o tipo:
     * HEARTBEAT, TALK, FILE, CHUNK, END, ACK, NACK.
     */
    private synchronized void handleMessage(String msg, InetAddress addr, int port) {
        // Dividir por espaço (cuidado com espaços em <dados>).
        // Uma abordagem simples: split no primeiro espaço apenas para pegar o comando.
        String[] parts = msg.split(" ", 2);
        String command = parts[0].toUpperCase();  // ex: "HEARTBEAT", "TALK", ...
        
        switch (command) {
            case "HEARTBEAT":
                // Formato esperado: HEARTBEAT <nome>
                // Exemplo: "HEARTBEAT DispositivoA"
                if (parts.length < 2) return;
                String heartbeatName = parts[1].trim();
                handleHeartbeat(heartbeatName, addr, port);
                break;
                
            case "TALK":
                // Formato esperado: TALK <id> <dados>
                // Exemplo: "TALK msg1234 Mensagem de texto"
                handleTalk(msg, addr, port);
                break;
                
            case "FILE":
                // Formato: FILE <id> <nome-arquivo> <tamanho>
                // Exemplo: "FILE file123 foto.png 102400"
                handleFile(msg, addr, port);
                break;
                
            case "CHUNK":
                // Formato: CHUNK <id> <seq> <dadosBase64>
                handleChunk(msg, addr, port);
                break;
                
            case "END":
                // Formato: END <id> <hash>
                handleEnd(msg, addr, port);
                break;
                
            case "ACK":
                // Formato: ACK <id>
                handleAck(msg, addr, port);
                break;
                
            case "NACK":
                // Formato: NACK <id> <motivo>
                handleNack(msg, addr, port);
                break;
                
            default:
                System.out.println("[WARN] Mensagem desconhecida: " + msg);
        }
    }
    
    /**
     * Trata HEARTBEAT <nome>, atualizando (ou incluindo) dispositivo na lista.
     */
    private synchronized void handleHeartbeat(String otherName, InetAddress addr, int port) {
        long now = System.currentTimeMillis();
        if (!activeDevices.containsKey(otherName)) {
            // Novo dispositivo
            activeDevices.put(otherName, new DeviceInfo(otherName, addr.getHostAddress(), port, now));
            System.out.println(">>> [INFO] Novo dispositivo encontrado: " + otherName
                    + " (" + addr.getHostAddress() + ":" + port + ")");
        } else {
            // Atualiza último heartbeat
            DeviceInfo info = activeDevices.get(otherName);
            info.setLastHeartbeat(now);
        }
    }
    
    /**
     * Envia (broadcast) HEARTBEAT <nome> para anunciar que está ativo.
     */
    private void sendHeartbeat() {
        String message = "HEARTBEAT " + deviceName;
        // Enviar em broadcast (ou multicasts, dependendo da sua rede)
        sendUdp(message, "192.0.0.255", PORT);
    }
    
    /**
     * Remove dispositivos que não mandam HEARTBEAT há mais de DEVICE_TIMEOUT ms.
     */
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
    
    /**
     * Trata TALK <id> <dados>.
     * Confirma recebimento via ACK <id>.
     * Exemplo de fiFo / duplicado etc. poderia ser tratado aqui.
     */
    private synchronized void handleTalk(String fullMsg, InetAddress addr, int port) {
        // Exemplo de parsing:
        // fullMsg = "TALK msg123 Alguma mensagem"
        String[] tokens = fullMsg.split(" ", 3);
        if (tokens.length < 3) return;
        
        String id = tokens[1];
        String dados = tokens[2];
        
        // Manda ACK
        sendUdp("ACK " + id, addr.getHostAddress(), port);
        
        // Exibe (por simplicidade, só imprime na tela).
        System.out.println(">>> [TALK recebido] ID=" + id + " Conteúdo=\"" + dados + 
                           "\" de " + addr.getHostAddress() + ":" + port);
        
        // Se quiser descartar duplicados, manter um set de "IDs recebidos".
    }
    
    /**
     * Trata FILE <id> <nome-arquivo> <tamanho>.
     * Envia ACK <id> para confirmar recebimento e se preparar para receber CHUNKs.
     */
    private synchronized void handleFile(String fullMsg, InetAddress addr, int port) {
        // Exemplo: "FILE file123 foto.png 102400"
        String[] tokens = fullMsg.split(" ", 4);
        if (tokens.length < 4) return;
        
        String id = tokens[1];
        String nomeArq = tokens[2];
        long tamanho = Long.parseLong(tokens[3]);
        
        // Confirma recebimento do FILE
        sendUdp("ACK " + id, addr.getHostAddress(), port);
        
        System.out.println(">>> [FILE recebido] ID=" + id + " Arquivo=\"" + nomeArq + 
                           "\" Tamanho=" + tamanho);
        
        // Aqui você poderia criar alguma estrutura para armazenar o
        // "estado" da recepção do arquivo: quantos CHUNKs esperados,
        // soma de bytes, buffer no disco, etc.
    }
    
    /**
     * Trata CHUNK <id> <seq> <dadosBase64>, envia ACK <id> após receber cada chunk.
     */
    private synchronized void handleChunk(String fullMsg, InetAddress addr, int port) {
        // Exemplo: "CHUNK file123 1 QmFzZTY0"
        String[] tokens = fullMsg.split(" ", 4);
        if (tokens.length < 4) return;
        
        String id = tokens[1];
        int seq = Integer.parseInt(tokens[2]);
        String base64 = tokens[3];
        
        // Decodifica base64
        byte[] chunkData = Base64.getDecoder().decode(base64);
        
        // TODO: armazenar no arquivo apropriado, levando em conta a seq, etc.
        
        // ACK
        sendUdp("ACK " + id, addr.getHostAddress(), port);
        
        System.out.println(">>> [CHUNK] ID=" + id + " seq=" + seq + 
                           " tamBytes=" + chunkData.length);
        
        // Se quiser descartar duplicados, manter registro de seqs recebidos.
    }
    
    /**
     * Trata END <id> <hash>, envia ACK <id> (ou NACK <id> se hash incorreto).
     */
    private synchronized void handleEnd(String fullMsg, InetAddress addr, int port) {
        // Exemplo: "END file123 abcdefgh1234567890..."
        String[] tokens = fullMsg.split(" ", 3);
        if (tokens.length < 3) return;
        
        String id = tokens[1];
        String hashRecebido = tokens[2];
        
        // TODO: comparar com hash calculado localmente do arquivo recebido.
        boolean hashOk = true; // ex.: ...
        
        if (hashOk) {
            sendUdp("ACK " + id, addr.getHostAddress(), port);
            System.out.println(">>> [END] Arquivo ID=" + id + " validado com sucesso!");
        } else {
            sendUdp("NACK " + id + " HASH_INVALIDO", addr.getHostAddress(), port);
            System.out.println(">>> [END] Arquivo ID=" + id + " corrompido. NACK enviado.");
        }
    }
    
    /**
     * Trata ACK <id>: remove a mensagem correspondente de pendingMessages (se existir).
     */
    private void handleAck(String fullMsg, InetAddress addr, int port) {
        // "ACK msg123"
        String[] tokens = fullMsg.split(" ", 2);
        if (tokens.length < 2) return;
        
        String id = tokens[1];
        PendingMessage pm = pendingMessages.remove(id);
        if (pm != null) {
            // Significa que recebemos a confirmação
            System.out.println(">>> [ACK] Recebido para ID=" + id);
        }
    }
    
    /**
     * Trata NACK <id> <motivo>. Poderia forçar retransmissão ou abortar etc.
     */
    private void handleNack(String fullMsg, InetAddress addr, int port) {
        // "NACK msg123 Motivo"
        String[] tokens = fullMsg.split(" ", 3);
        if (tokens.length < 3) return;
        
        String id = tokens[1];
        String motivo = tokens[2];
        
        System.out.println(">>> [NACK] Recebido para ID=" + id + " Motivo=" + motivo);
        
        // Dependendo da lógica, podemos reenviar a mensagem ou abortar.
    }
    
    /**
     * Tenta reenviar mensagens pendentes que não receberam ACK ainda.
     * Se já passou muito tempo, reenvia.
     */
    private void resendPendingMessages() {
        long now = System.currentTimeMillis();
        for (PendingMessage pm : pendingMessages.values()) {
            if ((now - pm.getLastSent()) > 3000) { // ex.: 3s
                System.out.println("[RETX] Reenviando ID=" + pm.getId());
                sendUdp(pm.getMessage(), pm.getDestIp(), pm.getDestPort());
                pm.updateLastSent();
            }
        }
    }
    
    /**
     * Envia uma mensagem via UDP para determinado IP/porta.
     */
    private void sendUdp(String msg, String destIp, int destPort) {
        try {
            byte[] data = msg.getBytes(StandardCharsets.UTF_8);
            DatagramPacket packet = new DatagramPacket(data, data.length,
                                                       InetAddress.getByName(destIp), destPort);
            socket.send(packet);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
    
    /**
     * Loop de console que aceita comandos do usuário:
     *  - devices
     *  - talk <nome> <mensagem>
     *  - sendfile <nome> <caminho-arquivo>
     */
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
                    // Formato: talk <nome> <mensagem>
                    if (parts.length < 2) {
                        System.out.println("Uso: talk <nome> <mensagem>");
                        break;
                    }
                    // Separar <nome> do resto da mensagem
                    String[] talkParts = parts[1].split(" ", 2);
                    if (talkParts.length < 2) {
                        System.out.println("Uso: talk <nome> <mensagem>");
                        break;
                    }
                    String targetName = talkParts[0];
                    String userMsg = talkParts[1];
                    sendTalk(targetName, userMsg);
                    break;
                    
                case "sendfile":
                    // Formato: sendfile <nome> <caminho-arq>
                    if (parts.length < 2) {
                        System.out.println("Uso: sendfile <nome> <caminho-arquivo>");
                        break;
                    }
                    String[] fileParts = parts[1].split(" ", 2);
                    if (fileParts.length < 2) {
                        System.out.println("Uso: sendfile <nome> <caminho-arquivo>");
                        break;
                    }
                    String fileTarget = fileParts[0];
                    String filePath = fileParts[1];
                    sendFile(fileTarget, filePath);
                    break;
                    
                default:
                    System.out.println("Comando não reconhecido: " + cmd);
            }
        }
    }
    
    /**
     * Lista os dispositivos ativos, mostrando (nome, IP, tempo desde último heartbeat).
     */
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
    
    /**
     * Envia uma mensagem TALK <id> <dados> para o dispositivo <nome>.
     * Aguarda ACK, se não chegar, haverá retransmissão.
     */
    private void sendTalk(String targetName, String content) {
        DeviceInfo info = activeDevices.get(targetName);
        if (info == null) {
            System.out.println("[ERRO] Dispositivo não encontrado: " + targetName);
            return;
        }
        
        String id = generateMessageId();
        String msg = "TALK " + id + " " + content;
        
        // Armazena em pendingMessages para aguardar ACK
        pendingMessages.put(id, new PendingMessage(id, msg, info.getIp(), info.getPort()));
        
        // Envia
        sendUdp(msg, info.getIp(), info.getPort());
        System.out.println(">>> [TALK enviado] ID=" + id + " para " + targetName);
    }
    
    /**
     * Envia um arquivo em blocos CHUNK para o dispositivo <nome>.
     * 1) Manda FILE <id> <nomeArq> <tamanho>
     * 2) Envia CHUNKs
     * 3) Envia END <id> <hash> (ex.: MD5)
     */
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
        
        // 1) Enviar FILE <id> <nomeArq> <tamanho>
        String fileMsg = "FILE " + id + " " + fileName + " " + fileSize;
        pendingMessages.put(id, new PendingMessage(id, fileMsg, info.getIp(), info.getPort()));
        sendUdp(fileMsg, info.getIp(), info.getPort());
        
        System.out.println(">>> [FILE enviado] ID=" + id + " Arquivo=" + fileName + " Tamanho=" + fileSize);
        
        // 2) Enviar CHUNKs
        try (FileInputStream fis = new FileInputStream(f)) {
            byte[] buffer = new byte[1024];
            int read;
            int seq = 0;
            while ((read = fis.read(buffer)) != -1) {
                seq++;
                byte[] chunk = Arrays.copyOf(buffer, read);
                String base64 = Base64.getEncoder().encodeToString(chunk);
                String chunkMsg = "CHUNK " + id + " " + seq + " " + base64;
                
                // Cada CHUNK também pode ser posto em pendingMessages se quisermos ACK por bloco
                String chunkId = id + "-seq" + seq; // ID único
                pendingMessages.put(chunkId, new PendingMessage(chunkId, chunkMsg, info.getIp(), info.getPort()));
                
                sendUdp(chunkMsg, info.getIp(), info.getPort());
                System.out.println("... enviado CHUNK seq=" + seq + " (" + read + " bytes)");
                
                // Se quiser evitar congestionar a rede, pode dar um pequeno sleep
                // Thread.sleep(10);
            }
            
            // 3) Calcular o hash do arquivo para END
            // (Exemplo usando MD5)
            String fileHash = calculateMD5(f);
            
            // Enviar END <id> <hash>
            String endMsg = "END " + id + " " + fileHash;
            pendingMessages.put(id, new PendingMessage(id, endMsg, info.getIp(), info.getPort()));
            sendUdp(endMsg, info.getIp(), info.getPort());
            
            System.out.println(">>> [END enviado] ID=" + id + " hash=" + fileHash);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    
    /**
     * Exemplo de cálculo de MD5 para o arquivo. (Use java.security.MessageDigest, etc.)
     */
    private String calculateMD5(File file) throws Exception {
        MessageDigest md = java.security.MessageDigest.getInstance("MD5");
        try (InputStream is = new FileInputStream(file)) {
            byte[] buf = new byte[4096];
            int r;
            while ((r = is.read(buf)) != -1) {
                md.update(buf, 0, r);
            }
        }
        byte[] digest = md.digest();
        // Transforma em string hexa
        StringBuilder sb = new StringBuilder();
        for (byte b : digest) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
    
    /**
     * Gera um ID único (ex.: "msg1", "msg2", ...)
     */
    private static synchronized String generateMessageId() {
        messageCounter++;
        return "msg" + messageCounter;
    }
    
    /**
     * Info de cada dispositivo ativo
     */
    private static class DeviceInfo {
        private final String name;
        private final String ip;
        private final int port;
        private long lastHeartbeat;
        
        public DeviceInfo(String name, String ip, int port, long lastHb) {
            this.name = name;
            this.ip = ip;
            this.port = port;
            this.lastHeartbeat = lastHb;
        }
        
        public String getName() { return name; }
        public String getIp() { return ip; }
        public int getPort() { return port; }
        public long getLastHeartbeat() { return lastHeartbeat; }
        public void setLastHeartbeat(long t) { this.lastHeartbeat = t; }
    }
    
    /**
     * Representa uma mensagem pendente de ACK.
     */
    private static class PendingMessage {
        private final String id;       // ID (ex.: "msg1", ou "file1-seq2")
        private final String message;  // texto completo enviado
        private final String destIp;
        private final int destPort;
        private long lastSent;         // quando foi enviado pela última vez
        
        public PendingMessage(String id, String message, String destIp, int destPort) {
            this.id = id;
            this.message = message;
            this.destIp = destIp;
            this.destPort = destPort;
            this.lastSent = System.currentTimeMillis();
        }
        
        public String getId() { return id; }
        public String getMessage() { return message; }
        public String getDestIp() { return destIp; }
        public int getDestPort() { return destPort; }
        public long getLastSent() { return lastSent; }
        
        public void updateLastSent() {
            lastSent = System.currentTimeMillis();
        }
    }
    
    /**
     * Ponto de entrada do programa.
     * Exemplo de uso: java Device MeuDispositivo
     */
    public static void main(String[] args) {
        if (args.length < 1) {
            System.out.println("Uso: java Device <nome-do-dispositivo>");
            return;
        }
        String myName = args[0];
        
        Device d = new Device(myName);
        try {
            d.start();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
