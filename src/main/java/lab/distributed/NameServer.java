package lab.distributed;

import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBException;
import javax.xml.bind.Marshaller;
import javax.xml.bind.Unmarshaller;
import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.XmlRootElement;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.MulticastSocket;
import java.net.Socket;
import java.util.Arrays;
import java.util.TreeMap;

/**
 * Created by Ivan on 12/10/2016.
 */
@XmlRootElement(name = "nameserver")
public class NameServer implements NameServerInterface {

    @XmlElement(name = "nodemap")
    TreeMap<Integer, String> nodeMap = new TreeMap<>();

    /**
     * Multicast Config
     */
    public static final String GROUP = "225.1.2.3";
    public static final int MULTICAST_PORT = 12345;
    public static final int COMMUNICATIONS_PORT =  4000;

    public NameServer() {
        startMulticastListener();
    }

    public static NameServer fromDisk() {
        try {
            JAXBContext context = JAXBContext.newInstance(NameServer.class);
            Unmarshaller unmarshaller = context.createUnmarshaller();
            return (NameServer) unmarshaller.unmarshal(new File("nameserver.xml"));
        } catch (JAXBException e) {
            e.printStackTrace();
            System.out.println("File probably not found...");
            return null;
        }
    }

    public static final int hashName(String name) {
        return Math.abs(name.hashCode() % 32768);
    }

    /**
     * Voeg een node toe aan het systeem.
     *
     * @param nodeName    String, de naam van deze node
     * @param inetAddress String, het IP-adres van deze node.
     * @return Indien de node is toegevoegd, return true. Indien reeds een node met zelfde naaam, return false
     */
    public boolean addNode(String nodeName, String inetAddress) {
        if (!nodeMap.containsKey(hashName(nodeName))) {
            nodeMap.put(hashName(nodeName), inetAddress);
            saveToDisk();
            return true;
        } else
            return false;
    }

    /**
     * Verwijder een node uit het systeem
     *
     * @param nodeName String, de naam van de node
     * @return Geeft true terug als node is gevonden en verwijderd. Geeft false indien node niet gevonden.
     */
    public boolean removeNode(String nodeName) {
        return nodeMap.remove(hashName(nodeName)) != null;
    }

    /**
     * Vraagt de naam van de node op die "ownership" heeft over een bestand.
     *
     * @param filename Het pad van het bestand.
     * @return
     */
    public String getOwner(String filename) {
        int fileHash = hashName(filename);
        int closest = -1;
        for (Integer integer : nodeMap.keySet()) {
            if (integer > closest && integer < fileHash) {
                closest = integer;
            } else
                return nodeMap.get(closest == -1 ? nodeMap.lastKey() : closest);
        }
        if (closest != -1)
            return nodeMap.get(closest);
        else return null;
    }

    public void saveToDisk() {
        try {
            JAXBContext context = JAXBContext.newInstance(NameServer.class);
            Marshaller marshaller = context.createMarshaller();
            marshaller.setProperty(Marshaller.JAXB_FORMATTED_OUTPUT, true);
            marshaller.marshal(this, new File("nameserver.xml"));

            Unmarshaller unmarshaller = context.createUnmarshaller();
            NameServer xmlunmarshalled = (NameServer) unmarshaller.unmarshal(new File("nameserver.xml"));
        } catch (JAXBException e) {
            e.printStackTrace();
        }
    }

    /**
     * Start de multicast listener op. Ontvang multicasts van andere nodes en worden hier behandeld
     */
    public void startMulticastListener() {
        new Thread(new Runnable() {
            public void run() {
                try {
                    MulticastSocket multicastSocket = new MulticastSocket(MULTICAST_PORT);
                    multicastSocket.joinGroup(InetAddress.getByName(GROUP));
                    byte[] buf = new byte[256];
                    DatagramPacket datagramPacket = new DatagramPacket(buf, buf.length);
                    Socket socket;
                    while(true) {
                        multicastSocket.receive(datagramPacket);
                        byte[] byteAddress = Arrays.copyOfRange(buf, 0, 3);
                        String address = InetAddress.getByAddress(byteAddress).getHostAddress();
                        String name = new String(Arrays.copyOfRange(byteAddress, 4, 255)).trim();
                        System.out.println("Received multicast with IP "+address+" and name "+name);
                        addNode(name, address);
                        socket = new Socket(address, COMMUNICATIONS_PORT);
                        DataOutputStream dataOutputStream = new DataOutputStream(socket.getOutputStream());
                        dataOutputStream.writeUTF(""+nodeMap.size());
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }).start();
    }

    /**
     * Vraag het IP op van een node.
     * @param hash
     * @return
     */
    public String getAddress(int hash) {
        return nodeMap.get(hash);
    }


}
