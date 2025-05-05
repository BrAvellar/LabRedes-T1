public class Device {
    public static void main(String[] args) {
        if (args.length < 1) {
            System.out.println("Uso: java Device <nome-do-dispositivo>");
            return;
        }
        String myName = args[0];

        UdpNode node = new UdpNode(myName);
        try {
            node.start();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}