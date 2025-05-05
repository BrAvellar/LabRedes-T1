public class PendingMessage {
    private final String id;       
    private final String message;  
    private final String destIp;
    private final int destPort;
    private long lastSent;         

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
