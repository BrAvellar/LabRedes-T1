// Arquivo: PendingMessage.java
public class PendingMessage {
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
