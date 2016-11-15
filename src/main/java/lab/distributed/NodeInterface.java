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
}