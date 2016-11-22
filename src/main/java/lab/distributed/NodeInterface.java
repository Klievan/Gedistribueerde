package lab.distributed;

import java.rmi.Remote;
import java.rmi.RemoteException;

/**
 * Created by Ivan on 15/11/2016.
 */
public interface NodeInterface extends Remote {


    void setPreviousNode(int hash) throws RemoteException;

    void setNextNode(int hash) throws RemoteException;

    void printMessage(String message) throws RemoteException;

    void replicateNewFile(FileEntry entry) throws RemoteException;

    void setSize(String ip, int size) throws RemoteException;

    boolean changeLocalEntry(String name, FileEntry entry) throws RemoteException;

    boolean changeReplicatedEntry(String name, FileEntry entry) throws RemoteException;

    boolean deleteReplicatedFile(String naam) throws RemoteException;

}
