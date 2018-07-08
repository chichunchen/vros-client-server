import com.google.gson.Gson;
import org.jcodec.api.FrameGrab;
import org.jcodec.api.JCodecException;
import org.jcodec.common.io.NIOUtils;
import org.jcodec.common.model.Picture;
import org.jcodec.scale.AWTUtil;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.io.*;
import java.util.Vector;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * This class manage frame rendering, video segment downloading, and all bunch
 * of fov-logic.
 */
public class VRPlayer {
    Manifest manifest;
    private static final int TOTAL_SEG_FRAME = 10;
    private static final int USER_REQUEST_FREQUENCY = 30;
    public static final int VRPLAYER_WIDTH = 1200;
    public static final int VRPLAYER_HEIGHT = 1200;

    private String host;
    private int port;
    private String segmentPath;
    private String segFilename;
    private String traceFile;
    private JFrame vrPlayerFrame = new JFrame("VRPlayer");
    private JPanel mainPanel = new JPanel();
    private JLabel iconLabel = new JLabel();
    private ImageIcon icon;
    private Timer imageRenderingTimer;
    private int currFovSegTop;     // indicate the top video segment id could be decoded
    private FOVTraces fovTraces;    // use currFovSegTop to extract fov from fovTraces
    private ConcurrentLinkedQueue<Picture> frameQueue;

    /**
     * Construct a VRPlayer object which manage GUI, video segment downloading, and video segment decoding
     *
     * @param host        host of VRServer.
     * @param port        port to VRServer.
     * @param segmentPath path to the storage of video segments in a temporary path like tmp/.
     * @param segFilename file name of video segment.
     * @param trace       path of a user field-of-view trace file.
     */
    public VRPlayer(String host, int port, String segmentPath,
                    String segFilename, String trace) {
        this.host = host;
        this.port = port;
        this.segmentPath = segmentPath;
        this.segFilename = segFilename;
        this.traceFile = trace;
        this.frameQueue = new ConcurrentLinkedQueue<Picture>();

        // setup frame
        this.vrPlayerFrame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowOpened(WindowEvent windowEvent) {
                super.windowOpened(windowEvent);
            }
        });

        //Image display label
        iconLabel.setIcon(null);

        //frame layout
        mainPanel.setLayout(null);
        mainPanel.add(iconLabel);
        iconLabel.setBounds(0, 0, VRPLAYER_WIDTH, VRPLAYER_HEIGHT);

        vrPlayerFrame.getContentPane().add(mainPanel, BorderLayout.CENTER);
        vrPlayerFrame.setSize(new Dimension(VRPLAYER_WIDTH, VRPLAYER_HEIGHT));
        vrPlayerFrame.setVisible(true);

        imageRenderingTimer = new Timer(15, new guiTimerListener());
        imageRenderingTimer.setInitialDelay(0);
        imageRenderingTimer.setCoalesce(true);

        // if the currFovSegTop is larger than decodedSegTop then we could decode segment from
        // #decodedSegTop+1 to #currFovSegTop
        currFovSegTop = 0;

        // Setup user fov trace object
        fovTraces = new FOVTraces(traceFile);

        // Download manifest file and feed it into manifest object
        ManifestDownloader manifestDownloader = new ManifestDownloader(host, port, "manifest-client.txt");
        try {
            manifestDownloader.request();
        } catch (IOException e) {
            e.printStackTrace();
        }
        Gson gson = new Gson();
        try {
            BufferedReader bufferedReader = new BufferedReader(new FileReader("manifest-client.txt"));
            manifest = gson.fromJson(bufferedReader, Manifest.class);
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        }

        // Create network handler thread
        NetworkHandler networkHandler = new NetworkHandler();
        Thread networkThd = new Thread(networkHandler);

        // Start main thread gui timer, video decode thread and network handler thread
        imageRenderingTimer.start();
        networkThd.start();

        // Wait for all the worker thread to finish
        try {
            networkThd.join();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    /**
     * getSegFilenameFromId.
     *
     * @param id identifier of the video segment.
     * @return name of video segment.
     */
    public String getSegFilenameFromId(int id) {
        return Utilities.getSegmentName(segmentPath, segFilename, id);
    }

    /**
     * Download video segments or sending fov metadata in a separate thread.
     */
    private class NetworkHandler implements Runnable {
        // Request video segment every tick-tock
        public void run() {
            while (currFovSegTop + 1 <= manifest.getVideoSegmentAmount()) {
                int currLocalFovSegTop = currFovSegTop + 1;
                // 1. request fov with the key frame metadata from VRServer
                // TODO suppose one video segment have 10 frames temporarily, check out storage/segment.py
                int keyFrameID = (currLocalFovSegTop - 1) * TOTAL_SEG_FRAME;
                TCPSerializeSender metadataRequest = new TCPSerializeSender<FOVMetadata>(host, port, fovTraces.get(keyFrameID));
                metadataRequest.request();
                System.out.println("[[SEGMENT #" + currLocalFovSegTop + "]] send metadata to server");

                // 2. get response from VRServer which indicate "FULL" or "FOV"
                TCPSerializeReceiver msgReceiver = new TCPSerializeReceiver<Integer>(host, port);
                msgReceiver.request();
                int predPathMsg = (Integer) msgReceiver.getSerializeObj();
                System.out.println("[DEBUG] get size message: " + FOVProtocol.print(predPathMsg));

                // 3. download video segment from VRServer
                System.out.println("[DEBUG] download video segment");
                VideoSegmentDownloader videoSegmentDownloader =
                        new VideoSegmentDownloader(host, port, segmentPath, segFilename, currLocalFovSegTop,
                                (int) manifest.getVideoSegmentLength(currLocalFovSegTop));
                try {
                    videoSegmentDownloader.request();
                } catch (IOException e) {
                    e.printStackTrace();
                }
                // tell video decode thread the currSetTop has changed (new segment file has created)
                currFovSegTop = currLocalFovSegTop;

                // 3-1. check whether the other video frames (exclude key frame) does not match fov
                // 3-2. if any frame does not match, request full size video segment from VRServer with "BAD"
                // 3-2  if all the frames matches, send back "GOOD"
                if (FOVProtocol.isFOV(predPathMsg)) {
                    // compare all the user-fov frames exclude for key frame with the predicted fov
                    Vector<FOVMetadata> pathMetadataVec = manifest.getPredMetaDataVec().get(currLocalFovSegTop).getPathVec();
                    FOVMetadata pathMetadata = pathMetadataVec.get(predPathMsg);
                    int secondDownloadMsg = FOVProtocol.GOOD;
                    int totalDecodedFrame = 0;
                    // TODO fov file name should be corrected after storage has been prepared
                    String filename = getSegFilenameFromId(currFovSegTop);
                    File file = new File(getSegFilenameFromId(currFovSegTop));
                    FrameGrab grab = null;
                    // decode fov until fovTrace not match
                    try {
                        grab = FrameGrab.createFrameGrab(NIOUtils.readableChannel(file));
                        Picture picture;
                        while (null != (picture = grab.getNativeFrame())) {
                            FOVMetadata userFov = fovTraces.get(keyFrameID);
                            double coverRatio = pathMetadata.getOverlapRate(userFov);
                            if (coverRatio < FOVProtocol.THRESHOLD) {
                                System.out.println("fail at keyFrameID: " + keyFrameID);
                                System.out.println("user fov: " + userFov);
                                System.out.println("path metadata: " + pathMetadata);
                                System.out.println("overlap ratio: " + coverRatio);
                                secondDownloadMsg = FOVProtocol.BAD;
                                break;
                            }
                            frameQueue.add(picture);

                            keyFrameID++;
                            totalDecodedFrame++;
                        }
                    } catch (JCodecException je1) {
                        je1.printStackTrace();
                    } catch (IOException e1) {
                        e1.printStackTrace();
                    }

                    // send back GOOD for all-hit BAD for any fov-miss
                    TCPSerializeSender finRequest = new TCPSerializeSender<Integer>(host, port, secondDownloadMsg);
                    finRequest.request();
                    System.out.println("[DEBUG] send back " + FOVProtocol.print(secondDownloadMsg));

                    // receive full size video segment if send back BAD
                    if (secondDownloadMsg == FOVProtocol.BAD) {
                        videoSegmentDownloader =
                                new VideoSegmentDownloader(host, port, segmentPath, "workaround", currLocalFovSegTop,
                                        (int) manifest.getVideoSegmentLength(currLocalFovSegTop));
                        System.out.println("[DEBUG] request for full size video segment as compensation");
                        try {
                            videoSegmentDownloader.request();
                        } catch (IOException e) {
                            e.printStackTrace();
                        }

                        // TODO the file name of full size video segment is the same as fov video segment for now
                        System.out.println("[DEBUG] Start decode from frame: " + totalDecodedFrame);
                        filename = getSegFilenameFromId(currFovSegTop);
                        decodeWholeSegment(filename);
                    }
                } else if (FOVProtocol.isFull(predPathMsg)) {
                    // TODO the file name of full size video segment is the same as fov video segment for now
                    String filename = getSegFilenameFromId(currFovSegTop);
                    decodeWholeSegment(filename);
                } else {
                    // should never go here
                    assert (false);
                }

                System.out.println("---------------------------------------------------------");
            }
        }
    }

    /**
     * The main thread timer that render image from frame queue.
     */
    private class guiTimerListener implements ActionListener {
        public void actionPerformed(ActionEvent actionEvent) {
            if (!frameQueue.isEmpty()) {
                Picture picture = frameQueue.poll();
                if (picture != null) {
                    BufferedImage bufferedImage = AWTUtil.toBufferedImage(picture);
                    icon = new ImageIcon(bufferedImage);
                    iconLabel.setIcon(icon);
//                    System.out.println("[RENDER] Render icon, picture size: " + frameQueue.size());
                }
            }
        }
    }

    private void decodeWholeSegment(String filename) {
        File file = new File(filename);
        FrameGrab grab = null;
        try {
            grab = FrameGrab.createFrameGrab(NIOUtils.readableChannel(file));
            Picture picture;
            while (null != (picture = grab.getNativeFrame())) {
//                System.out.println("[DECODE] queue size: " + frameQueue.size());
//                System.out.println("[DECODE] " + filename + ": " + picture.getWidth() + "x" + picture.getHeight() + " " + picture.getColor());
                frameQueue.add(picture);
            }
        } catch (JCodecException je1) {
            je1.printStackTrace();
        } catch (IOException e1) {
            e1.printStackTrace();
        }
    }

    /**
     * Execute the VRplayer.
     *
     * @param args command line args.
     */
    public static void main(String[] args) {
        VRPlayer vrPlayer = new VRPlayer("localhost",
                1988,
                "tmp",
                "segment",
                "user-fov-trace.txt");
    }
}
