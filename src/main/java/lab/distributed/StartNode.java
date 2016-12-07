package lab.distributed;

import java.util.Scanner;

/**
 * Created by Ivan on 7/12/2016.
 */
public class StartNode {

    public static void main(String[] args) {
        Scanner scanner = new Scanner(System.in);
        System.out.printf("Enter node name: ");
        String name = scanner.nextLine();
        System.out.println("Do you want me to start rmiregistry.exe? (y/n)");
        if(scanner.nextLine().equalsIgnoreCase("y")) {
            Process process = NameServerBootstrapper.startRMIRegistry();
            Runtime.getRuntime().addShutdownHook(new Thread() {
                @Override
                public void run() {
                    System.out.println("Stopping rmiregistry.exe ...");
                    process.destroy();
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }
            });

        }
        System.out.println("Trying to start node with name: "+name);
        Node node = new Node(name);
        while(true) {
            String[] command = scanner.nextLine().split(" ");
            switch (command[0].toLowerCase()) {
                case "exit":
                    node.exit();
                    break;
                case "status":
                    if(node.getNameServer() != null) {
                        System.out.println("Nameserver has been found");
                    } else {
                        System.out.println("Nameserver has not been found yet.");
                    }
                    System.out.println("Previous node: "+node.getPreviousNode());
                    System.out.println("Next node: "+node.getNextNode());
                    break;
                case "fail":
                    System.out.println("Leaving the network without telling anyone...");
                    System.exit(1);
                    break;
                case "verifyrmi":
                    NameServerInterface nameServerInterface = node.getNameServer();
                    break;
                default:
                    System.out.println("Unknown command: "+command[0]);

            }
        }

    }

}