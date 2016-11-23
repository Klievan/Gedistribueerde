package lab.distributed;

import java.io.*;
import java.net.*;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.WatchEvent;
import java.rmi.AlreadyBoundException;
import java.rmi.Naming;
import java.rmi.NotBoundException;
import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.rmi.server.UnicastRemoteObject;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;

public class Node implements NodeInterface {

    public static final String GROUP = "225.1.2.3"; //multicast groep
    public static final int MULTICAST_PORT = 12345;
    public static final int FILESERVER_PORT = 4001;
    public static final int COMMUNICATIONS_PORT = 4000;
    public static final int PING_PORT = 9000;
    private String name;                            //naam van de node
    private int myHash;                             //hash van de node
    private String location;                        //ip adres van de node   
    private int previousNode = -1;                  //hash van de vorige node
    private int nextNode = -1;                      //hash van de huidige node
    private FileServer fileServer;                  //wordt gebruikt om bestanden te versturen over tcp
    private HashMap<String, FileEntry> localFiles, replicatedFiles; //key: naam, value: FileEntry
    private NameServerInterface nameServer;         //interface om de server via RMI te bereiken
    private WatchDir watchDir;
    private static final Path FILE_DIRECTORY = Paths.get("fileDirectory");

    /**
     * De constructor gaat een nieuwe node aanmaken in de nameserver met de gekozen naam en het ip adres van de machine waarop hij gestart wordt.
     *
     * @param name de naam van de node
     */
    public Node(String name) {
        this.name = name;
        this.myHash = hashName(name);
        try {   //adres van de host waarop de node gestart wordt
            location = InetAddress.getLocalHost().getHostAddress();
        } catch (UnknownHostException e) {
            e.printStackTrace();
        }

        startMulticastListener();
        try {
            Thread.sleep(500); // Start TCP socket half a second after multicast listener to prevent deadlock.
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        startRMI();
        try {
            Thread.sleep(500); // Start file server half a second after RMI to prevent deadlock.
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        fileServer = new FileServer(FILESERVER_PORT); //fileserver wordt opgestart

        try {
            Thread.sleep(500); // Send bootstrapbroadcast half a second after fileserver startup to prevent deadlock.
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        sendBootstrapBroadcast();   //jezelf broadcasten over het netwerk
        try {
            watchDir = new WatchDir(FILE_DIRECTORY, false, this);//watchdir class op FILE_DIRECTORY, niet recursief, op deze node
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * hash genereren van een bepaalde naam
     *
     * @param name de naam waarvan de hash wordt gegenereerd
     * @return de gegenereerde hash
     */
    public static final int hashName(String name) {
        return Math.abs(name.hashCode() % 32768);
    }

    /**
     * broadcast eigen adres en naam op het netwerk
     */
    private void sendBootstrapBroadcast() {
        try {
            byte[] addressData = Inet4Address.getByName(location).getAddress();
            byte[] nameData = name.getBytes();
            byte[] message = new byte[addressData.length + nameData.length];
            System.arraycopy(addressData, 0, message, 0, addressData.length);
            System.arraycopy(nameData, 0, message, addressData.length, nameData.length);
            sendMulticast(message);
        } catch (UnknownHostException e) {
            e.printStackTrace();
        }
    }

    /**
     * Deze node kan eender welke node verwijderen uit de nameServer
     *
     * @param hash de id van te verwijderen node
     */
    public void deleteNode(int hash) {
        try {
            if (!nameServer.removeNode(hash))
                System.out.println("Deze node bestaat niet");
        } catch (RemoteException e) {
            e.printStackTrace();
        }


    }

    /**
     * deze node wordt verwijderd uit de nameserver en sluit af
     * bestanden die hier gerepliceerd staan, worden gerepliceeerd naar de vorige node
     * Van de lokale bestanden wordt de eigenaar verwittigd of de downloadlocaties aangepast
     */
    public void exit() {
        ArrayList<String> downloads;
        FileEntry fileEntry;
        NodeInterface node;

        System.out.println("Leaving the network and updating my neighbours...");
        updateNode(previousNode, nextNode, "next");     //naar de previous node het id van de next node sturen
        updateNode(nextNode, previousNode, "prev");     //naar de next node het id van de previous node sturen

        //bestanden die hier gerepliceerd staan, repliceren naar de vorige node
        System.out.println("replicating my files to previous node...");
        for(HashMap.Entry<String, FileEntry> entry: replicatedFiles.entrySet())
        {
            fileEntry = entry.getValue();           //elke bestandsfiche een voor een aflopen
            try { //entry aanpassen
                fileEntry.setReplicated(nameServer.getAddress(previousNode));
                fileEntry.setOwner(nameServer.getAddress(previousNode));
            } catch (RemoteException e) {
                e.printStackTrace();
            }

            node = getNode(fileEntry.getLocal());   //node waar het bestand lokaal staat
            try { //bestandsfiche doorsturen naar lokale node
                if(!node.changeLocalEntry(fileEntry.getFileName(),fileEntry))
                    System.out.println("bestand kan niet aangepast worden want het bestaat niet...");
            } catch (RemoteException e) {
                e.printStackTrace();
            }
            //node waar het bestand naar gerepliceerd wordt
            node = getNode(previousNode);
            //file repliceren naar de vorige node
            try {
                node.replicateNewFile(fileEntry);
            } catch (RemoteException e) {
                e.printStackTrace();
            }
            //TODO: bestanden via tcp doorsturen naar vorige node
        }

        //Van de lokale bestanden wordt de eigenaar verwittigd of de downloadlocaties aangepast
        System.out.println("warning owner of local files...");
        for(HashMap.Entry<String, FileEntry> entry: localFiles.entrySet())
        {
            fileEntry = entry.getValue();
            node = getNode(fileEntry.getOwner()); //eigenaar van het bestand
            downloads=fileEntry.getDownloadLocations();

            if(downloads.isEmpty()) //nog nergens gedownload geweest
                try {
                    if(!node.deleteReplicatedFile(fileEntry.getFileName()))
                        System.out.println("bestand kan niet verwijderd worden want het bestaat niet");
                        //TODO: bestand lokaal van de schijf verwijderen
                } catch (RemoteException e) {
                    e.printStackTrace();
                }
            //else
            // TODO: downloadlocaties updaten dat de lokale node weg is?
            //Is de lokale node dan ook een downloadlocatie?
        }
        deleteNode(hashName(name));                     //node verwijderen uit de nameserver
        System.exit(0);                                 //systeem afsluiten
    }


    @Override
    public boolean deleteReplicatedFile(String naam) throws RemoteException
    {
        if(replicatedFiles.get(naam)!=null) {
            replicatedFiles.remove(naam);
            //TODO: verwijderen van harde schijf
            return true;
        }else
            return false;
    }


    @Override
    public void replicateNewFile(FileEntry entry)
    {
        String name = entry.getFileName();
        if (localFiles.get(name).equals(null)) //als het bestand nog niet lokaal bestaat
        {
            if (!entry.getLocalIsOwner())
                entry.setOwner(location);
            entry.setReplicated(location);
            replicatedFiles.put(name, entry);
        } else { //bestand bestaat lokaal en wordt gerepliceerd naar de vorige
            entry.setOwner(location);
            entry.setLocalIsOwner(true);
            NodeInterface node = getNode(previousNode);
            try {
                node.replicateNewFile(entry);
            } catch (RemoteException e) {
                e.printStackTrace();
            }
        }
    }
        //TODO: bestand zelf moet nog verzonden worden via tcp

    /**
     * Start de multicast listener op. Ontvang multicasts van andere nodes en worden hier behandeld
     */
    private void startMulticastListener() {
        new Thread(new Runnable() {
            public void run() {
                int hash = 0;
                try {
                    MulticastSocket multicastSocket = new MulticastSocket(MULTICAST_PORT);
                    multicastSocket.joinGroup(InetAddress.getByName(GROUP));
                    while (true) {
                        byte[] buf = new byte[256];
                        DatagramPacket datagramPacket = new DatagramPacket(buf, buf.length);
                        multicastSocket.receive(datagramPacket);
                        byte[] byteAddress = Arrays.copyOfRange(buf, 0, 4);
                        String address = InetAddress.getByAddress(byteAddress).getHostAddress();
                        String name = new String(Arrays.copyOfRange(buf, 4, 255)).trim();
                        hash = hashName(name);
                        NodeInterface node = getNode(address); //Vraag node op langs address want het kan zijn dat hij nog niet in de nameserver staat.
                        /**
                         * Ga eerst na of we de enigste node waren in het netwerk. Zo ja, zet vorige en volgende naar
                         * de nieuwe node, en zet die van de nieuwe node naar ons.
                         * https://gyazo.com/f0a9b650813f46d1b98ac63bb6b396fb
                         */
                        if (previousNode == myHash && nextNode == myHash) {
                            previousNode = hash;
                            nextNode = hash;
                            node.setNextNode(myHash);
                            node.setPreviousNode(myHash);
                            System.out.println("A second node has joined. I've set my previous and next node to him and updated him.");
                        }
                        /**
                         * Hierna gaan we na of de node tussen ons en één van onze buren ligt
                         */
                        else if ((myHash < hash && hash < nextNode) || (nextNode < myHash && (hash > myHash || hash < nextNode))) {
                            /**
                             * SITUATIE 1: (eerste deel van if-case)
                             * De node ligt tussen mij en mijn volgende buur. De nieuwe node is mijn volgende en ik ben
                             * de vorige van de nieuwe node. Ik zeg dit tegen de nieuwe node en pas mijn volgende aan.
                             */
                            /**
                             * SITUATIE 2: (tweede monstreuze deel van if-case)
                             * Ik zit aan het einde van de kring want mijn volgende node is lager dan mij.
                             * De nieuwe node ligt boven mij, of ligt onder mijn volgende (laagste) node. Ik licht
                             * de nieuwe node in over zijn buren en pas mijn volgende aan.
                             */
                            node.setPreviousNode(myHash);
                            node.setNextNode(nextNode);
                            System.out.printf("A node (%d) joined between me (%d) and my next neighbour (%d). Updating accordingly...\nWelcome %s!\n", hash, myHash, nextNode, name);
                            nextNode = hash;
                        } else if ((previousNode < hash && hash < myHash) || (previousNode > myHash && (hash < myHash || hash > nextNode))) {//TODO mogelijk fout, laatste stuk ...|| hash > previousNode?
                            /**
                             * De node ligt tussen mijn vorige buur en mij. Mijn vorige buur zal de nieuwe node
                             * over zijn nieuwe buren informeren. Ik pas enkel mijn vorige node aan.
                             */
                            System.out.printf("A node (%d) joined between my previous neighbour (%d) and me. Updating accordingly...\nWelcome %s!\n", hash, previousNode, name);
                            previousNode = hash;
                        } else if (hash == myHash) {
                            System.out.printf("I joined the network.\n");
                        } else {
                            System.out.printf("A node (%d) joined but isn't between my previous or next neighbour.\nWelcome %s!\n", hash, name);
                        }
                    }
                } catch (UnknownHostException e) {
                    e.printStackTrace();
                } catch (IOException e) {
                    e.printStackTrace();
                    failure(hash);
                }
            }
        }).start();
    }

    /**
     * Verstuur een multicast bericht naar alle nodes en nameserver met message als bericht
     *
     * @param message het bericht dat verzonden moet worden
     */
    private void sendMulticast(byte[] message) {
        DatagramSocket datagramSocket = null;
        try {
            datagramSocket = new DatagramSocket(MULTICAST_PORT, InetAddress.getLocalHost());
            datagramSocket.send(new DatagramPacket(message, message.length, InetAddress.getByName(GROUP), MULTICAST_PORT));
            System.out.println("Multicast sent from " + name);
            datagramSocket.close();
        } catch (IOException e) {
            e.printStackTrace();
        }

    }

    @Override
    public void setPreviousNode(int hash) {
        this.previousNode = hash;
    }

    @Override
    public void setNextNode(int hash) {
        this.nextNode = hash;
    }

    @Override
    public void printMessage(String message) throws RemoteException {
        System.out.println(message);
    }

    /**
     * de methode die moet aangeroepen worden wanneer de communicatie met een Node mislukt is
     *
     * @param hash het id van de node waarmee de communicatie mislukt is
     */
    private void failure(int hash) {
        try {
            int nextNode = nameServer.getNextNode(hash);
            int previousNode = nameServer.getPreviousNode(hash);

            updateNode(previousNode, nextNode, "next");       //naar de previous node het id van de next node sturen
            updateNode(nextNode, previousNode, "prev");       //naar de next node het id van de previous node sturen
            deleteNode(hash);                                 //node verwijderen
        } catch (RemoteException e) {
            e.printStackTrace();
        }
    }

    /**
     * Met deze methode wordt de de volgende of vorige node van een bepaalde node aangepast
     *
     * @param target     de node waarin de parameters worden aangepast
     * @param changed    de nieuwe waarde voor de parameter (hash)
     * @param nextPrev   moet de volgende of de vorige node aangepast worden? kan waarde "next" of "prev" aannemen
     */
    private void updateNode(int target, int changed, String nextPrev) {
        Socket socket;
        DataOutputStream dataOutputStream;
        try {
            socket = new Socket(nameServer.getAddress(target), COMMUNICATIONS_PORT);
            dataOutputStream = new DataOutputStream(socket.getOutputStream());
            dataOutputStream.writeUTF(nextPrev + " " + changed); //als nextPrev een verkeerde waarde heeft wordt dit opgevangen in de listener
            dataOutputStream.close();
        } catch (MalformedURLException | UnknownHostException | RemoteException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
            failure(target);
        }
    }

    /**
     * dit is slechts een testmethode om de failure methode op te roepen.
     */
    public void sendPing() {
        try {
            Socket socket = new Socket(nameServer.getAddress(nextNode), PING_PORT);
            DataOutputStream dataOutputStream = new DataOutputStream(socket.getOutputStream());
            DataInputStream dataInputStream = new DataInputStream(socket.getInputStream());
            while (true) {
                dataOutputStream.writeUTF("ping");
                String pong = dataInputStream.readUTF();
                if (pong.equals("pong")) {
                    dataOutputStream.writeUTF("ping");
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
            failure(nextNode);
        }
    }

    /**
     * Testmethode om bij sendping methode te gaan, persoon die zal ontvangen moet eerst receiveping starten, persoon die zal senden moet dan sendping starten, kabel uittrekken van persoon die receiveping draait.
     */
    public void receivePing() {
        try {
            ServerSocket serverSocket = new ServerSocket(PING_PORT);
            Socket clientSocket = serverSocket.accept();
            DataInputStream dataInputStream = new DataInputStream(clientSocket.getInputStream());
            DataOutputStream dataOutputStream = new DataOutputStream(clientSocket.getOutputStream());
            while (true) {
                String ping = dataInputStream.readUTF();
                if (ping.equals("ping")) {
                    dataOutputStream.writeUTF("pong");
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * Vraag een bestand op naar een andere node. De bestanden worden gezocht in de subfolder ./files en zullen op de
     * eigen node ook in deze map geplaatst worden.
     *
     * @param node      De hash van de node
     * @param filename  Naam van het bestand
     * @return          Of het bestand gevonden was of niet, of dat de node niet bestaat.
     */
    public boolean requestFile(int node, String filename) {
        try {
            return requestFile(nameServer.getAddress(node), filename);
        } catch (RemoteException e) {
            e.printStackTrace();
        }
        return false;
    }

    /**
     * Verstuur een bestand naar een andere node. De bestanden worden gezocht in de subfolder ./files en zullen op de
     * destination ook in deze map geplaatst worden.
     *
     * @param node      Hash van de node
     * @param filename  Bestandsnaam
     * @return          Of dat de server het bestand successvol heeft ontvangen
     */
    public boolean sendFile(int node, String filename) {
        try {
            return sendFile(nameServer.getAddress(node), filename);
        } catch (RemoteException e) {
            e.printStackTrace();
        }
        return false;
    }

    /**
     * Vraag een bestand op van een IP address. Kijk naar requestFile(int, String voor meer uitleg)
     *
     * @param address
     * @param filename
     * @return
     */
    public boolean requestFile(String address, String filename) {
        try {
            Socket socket = new Socket(address, FILESERVER_PORT);
            DataInputStream dataInputStream = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
            DataOutputStream dataOutputStream = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
            dataOutputStream.writeUTF("send");
            dataOutputStream.writeUTF(filename);
            FileOutputStream fileOutputStream = new FileOutputStream("./files/" + filename);
            byte[] bytes = new byte[8192];
            int count;
            while ((count = dataInputStream.read(bytes)) > 0) {
                fileOutputStream.write(bytes, 0, count);
            }
            dataOutputStream.close();
            dataInputStream.close();
            socket.close();
            return true;

        } catch (IOException e) {
            return false;
            //e.printStackTrace();
        }
    }

    public boolean sendFile(String address, String filename) {
        try {
            Socket socket = new Socket(address, FILESERVER_PORT);
            DataInputStream dataInputStream = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
            DataOutputStream dataOutputStream = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
            dataOutputStream.writeUTF("receive");
            dataOutputStream.writeUTF(filename);
            FileInputStream fileInputStream = new FileInputStream("./files/" + filename);
            byte[] bytes = new byte[8192];
            int count;
            while ((count = fileInputStream.read(bytes)) > 0) {
                dataOutputStream.write(bytes, 0, count);
            }
            dataOutputStream.close();
            dataInputStream.close();
            socket.close();
            return true;

        } catch (IOException e) {
            return false;
            //e.printStackTrace();
        }
    }

    /**
     * Pas een bestandsfiche van een lokaal bestand aan
     * @param name de bestandsnaam
     * @param entry de nieuwe entry
     * @return true als het bestand bestaat, false als het niet bestaat
     */
    public boolean changeLocalEntry(String name, FileEntry entry) throws RemoteException
    {
        if(localFiles.get(name) != null)
        {
            localFiles.put(name,entry);
            return true;
        }
        else
            return false;
    }

    /**
     * Pas een entry van een replicated bestand aan
     * @param name de bestandsnaam
     * @param entry de nieuwe entry
     * @return true als het bestand bestaat, false als het niet bestaat
     */
    public boolean changeReplicatedEntry(String name, FileEntry entry) throws RemoteException
    {
        if(replicatedFiles.get(name) != null)
        {
            replicatedFiles.put(name,entry);
            return true;
        }
        else
            return false;
    }

    /**
     * Methode die wordt aangeroepen in de constructor om RMI op te starten
     */
    private void startRMI() {
        try {
            NodeInterface nodeInterface = (NodeInterface) UnicastRemoteObject.exportObject(this, 0);
            Registry registry = LocateRegistry.getRegistry();
            registry.bind("NodeInterface", nodeInterface);
            System.out.println("Started RMI. Ready for connections...");
        } catch (RemoteException | AlreadyBoundException e) {
            e.printStackTrace();
        }
    }

    /**
     * Geeft de interface terug van een Node zodat we deze node via RMI kunnen bereiken
     * @param hash de hash van de gewenste Node
     * @return de interface die we gebruiken om RMI aan te roepen
     */
    public NodeInterface getNode(int hash) {
        try {
            return getNode(nameServer.getAddress(hash));
        } catch (RemoteException e) {
            e.printStackTrace();
        }
        return null;
    }

    /**
     * Geeft de interface terug van een Node zodat we deze node via RMI kunnen bereiken
     * @param IP het ip adres van de gewenste Node
     * @return de interface die we gebruiken om RMI aan te roepen
     */
    public NodeInterface getNode(String IP) {
        String name = String.format("//%s/NodeInterface", IP);
        try {
            return (NodeInterface) Naming.lookup(name);
        } catch (NotBoundException | MalformedURLException | RemoteException e) {
            e.printStackTrace();
        }
        return null;
    }

    @Override
    public void setSize(String ip, int size) {
        if(size == -1) {
            System.out.println("Nameserver rejected our node because of duplicate entry. Quitting...");
            System.exit(1);
        } else {
            System.out.println("Nameserver replied from " + ip);
            connectToNameServer(ip);
            if (size == 1) {
                System.out.println("I'm the first node. I'm also the previous and next node. ");
                previousNode = myHash;
                nextNode = myHash;
            } else {
                System.out.printf("I'm not the first node (size is %d). Waiting for my next and previous node...\n", size);
            }
        }
    }

    /**
     * verbinding maken met de nieuwe nameserver
     * @param IP Ip van de nameserver
     */
    private void connectToNameServer(String IP) {
        System.out.println("Attempting to connect to NameServer");
        String rmiName = "//" + IP + "/NameServerInterface";
        try {
            nameServer = (NameServerInterface) Naming.lookup(rmiName);
        } catch (NotBoundException | MalformedURLException | RemoteException e) {
            e.printStackTrace();
        }
        System.out.println("Successfully connected to NameServer!");
    }

    /**
     * Method die door WatchDir opgeroepen wordt wanneer een event plaatsvindt
     * @param eventType type van event
     * @param fileName de filename, in String, met extensie
     */
    public void directoryChange(String eventType,String fileName) {
        String node;
        switch (eventType) {
            case "ENTRY_CREATE":
                try {
                    String owner = nameServer.getOwner(fileName);//bij fileserver opvragen op welke node dit bestand gerepliceerd moet worden IP krijgen we terug
                    FileEntry fileEntry = new FileEntry(fileName, location, owner, owner);
                    NodeInterface nodeInterface = getNode(owner);
                    nodeInterface.replicateNewFile(fileEntry);
                    if(!owner.equals(location)){
                        sendFile(owner, fileName);
                    }
                    else{
                        sendFile(previousNode, fileName);
                    }
                } catch (RemoteException e) {
                    e.printStackTrace();
                }
                break;
            case "ENTRY_DELETE":
                /*
                  nog niet gespecifieerd in de opgave.
                 */
                break;
        }
    }

    /**
     * Wanneer een nieuwe node start moet de vorige node (deze node bijvoorbeeld) nagaan of
     * er bestanden zijn die hier gerepliceerd zijn, die dan naar de nieuwe node gerepliceerd
     * moeten worden. Zoja, bestandsfiche updaten en via TCP doorsturen
     */
    public void  checkOwnedFilesOnDiscovery(){
        
    }
}