import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;

/**
 * This object is a server sending manifest file and video segments to VRPlayer.
 */
public class VRServer implements Runnable {
    private static final int BUF_SIZE = 4096;

    private Socket clientSock;
    private ServerSocket ss;
    private String videoSegmentDir;
    private String filename;
    private boolean hasSentManifest;
    private Manifest manifestCreator;

    /**
     * Setup a VRServer object that waiting for connections from VRPlayer.
     *
     * @param port            port of the VRServer.
     * @param videoSegmentDir path to the storage of video segments.
     * @param filename        name of video segments.
     */
    public VRServer(int port, String videoSegmentDir, String filename) {
        // init
        this.videoSegmentDir = videoSegmentDir;
        this.filename = filename;

        // setup a tcp server socket that waiting for sending files
        try {
            ss = new ServerSocket(port);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * Send file to the specified socket.
     *
     * @param sock socket of the client.
     * @param file filename of a video segment.
     * @throws IOException when dataOutputStream fails to write or fileInputStream fails to read.
     */
    private void sendFile(Socket sock, String file) throws IOException {
        DataOutputStream dos = new DataOutputStream(sock.getOutputStream());
        FileInputStream fis = new FileInputStream(file);
        byte[] buffer = new byte[BUF_SIZE];

        System.out.println("Send " + file + " from VRServer");

        while (fis.read(buffer) > 0) {
            dos.write(buffer);
        }

        fis.close();
        dos.close();
    }

    /**
     * Accept the connection from VRPlayer and then send the manifest file or video segments.
     */
    public void run() {
        while (true) {
            if (this.hasSentManifest) {
                // send video segments
                for (int i = 1; i <= manifestCreator.getVideoSegmentAmount(); i++) {
                    try {
                        // get user fov metadata (key frame)
                        TCPSerializeReceiver<FOVMetadata> fovMetadataTCPSerializeReceiver = new TCPSerializeReceiver<FOVMetadata>(ss);
                        fovMetadataTCPSerializeReceiver.request();
                        System.out.println("Get user fov: " + fovMetadataTCPSerializeReceiver.getSerializeObj());

                        // TODO inspect storage to know if there is a matched video segment, if yes, send  the most-match FOV, no, send FULL
                        int videoSizeMsg = 4;
                        TCPSerializeSender<Integer> msgRequest = new TCPSerializeSender<Integer>(this.ss, videoSizeMsg);
                        msgRequest.request();

                        // send video segment
                        clientSock = ss.accept();
                        sendFile(clientSock, Utilities.getSegmentName(videoSegmentDir, this.filename, i));

                        // TODO wait for "GOOD" or "BAD"
                        // TODO GOOD: continue the next iteration
                        // TODO BAD: send back full size video segment
                        if (FOVProtocol.isFOV(videoSizeMsg)) {
                            TCPSerializeReceiver<Integer> finReceiver = new TCPSerializeReceiver<Integer>(ss);
                            finReceiver.request();
                            int finMsg = finReceiver.getSerializeObj();
                            System.out.println("fin message: " + FOVProtocol.print(finMsg));

                            if (finMsg == FOVProtocol.BAD) {
                                // TODO
                            }
                        }
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            } else {
                // create manifest file for VRServer to send to VRPlayer
                manifestCreator = new Manifest("storage/rhino/", "storage/rhinos-pred.txt");
                try {
                    manifestCreator.write("manifest-server.txt");
                } catch (IOException e) {
                    e.printStackTrace();
                }

                // send the manifest file just created
                System.out.println("Manifest file size: " + new File("manifest-server.txt").length());
                try {
                    this.clientSock = ss.accept();
                    sendFile(clientSock, "manifest-server.txt");
                } catch (IOException e) {
                    e.printStackTrace();
                }

                this.hasSentManifest = true;
            }
        }
    }

    /**
     * Usage java VRServer {dir} {filename}
     * The file name in the dir will be constructed as {filename}_number.mp4.
     *
     * @param args command line args.
     */
    public static void main(String[] args) {
        VRServer vrServer = new VRServer(1988, "storage/rhino", "output");
        vrServer.run();
    }
}
