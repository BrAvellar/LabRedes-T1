public class DeviceInfo {
    private final String name;
    private final String ip;
    private final int port;
    private long lastHeartbeat;

    public DeviceInfo(String name, String ip, int port, long lastHeartbeat) {
        this.name = name;
        this.ip = ip;
        this.port = port;
        this.lastHeartbeat = lastHeartbeat;
    }

    public String getName() { return name; }
    public String getIp() { return ip; }
    public int getPort() { return port; }
    public long getLastHeartbeat() { return lastHeartbeat; }
    public void setLastHeartbeat(long t) { this.lastHeartbeat = t; }
} 
    
