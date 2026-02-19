import java.util.HashMap;
import java.util.Map;
import java.util.Scanner;

// 1. The Subject Interface (The contract)
interface VideoStreamer {
    void playVideo(String videoId);
}

// 2. The Real Subject (Heavy Resource - BLOB Storage)
class RealVideoPlayer implements VideoStreamer {
    private String manifestFile;

    public RealVideoPlayer(String manifestFile) {
        this.manifestFile = manifestFile;
        loadFromBlobStorage();
    }

    private void loadFromBlobStorage() {
        System.out.println("[BLOB Storage] Establishing high-bandwidth connection...");
        System.out.println("[BLOB Storage] Buffering initial segments from: " + manifestFile);
    }

    @Override
    public void playVideo(String videoId) {
        System.out.println("[Streaming] Video " + videoId + " is now playing via " + manifestFile);
    }
}

// 3. The Proxy (Intermediary - Metadata & Lazy Loading)
class VideoProxy implements VideoStreamer {
    private RealVideoPlayer realPlayer;
    private static Map<String, VideoMetadata> mongoDB = new HashMap<>();

    static {
        // Simulating metadata stored in MongoDB
        mongoDB.put("vid_101", new VideoMetadata("System Design 101", "HLD Basics", "playlist.m3u8"));
    }

    @Override
    public void playVideo(String videoId) {
        // Fetch lightweight metadata first (Proxy logic)
        VideoMetadata meta = mongoDB.get(videoId);
        
        if (meta == null) {
            System.out.println("Error: Video metadata not found in MongoDB.");
            return;
        }

        System.out.println("\n--- UI Metadata Loaded (from MongoDB) ---");
        System.out.println("Title: " + meta.title);
        System.out.println("Description: " + meta.description);
        System.out.println("Manifest: " + meta.manifest);
        System.out.println("------------------------------------------\n");

        // Lazy Initialization: Only create the heavy player when play is confirmed
        Scanner scanner = new Scanner(System.in);
        System.out.print("Press 'P' to start streaming from BLOB storage: ");
        String choice = scanner.next();

        if (choice.equalsIgnoreCase("p")) {
            if (realPlayer == null) {
                realPlayer = new RealVideoPlayer(meta.manifest);
            }
            realPlayer.playVideo(videoId);
        } else {
            System.out.println("Streaming cancelled. Keeping heavy resources idle.");
        }
    }
}

// Helper class for Metadata
class VideoMetadata {
    String title, description, manifest;
    VideoMetadata(String t, String d, String m) {
        this.title = t; this.description = d; this.manifest = m;
    }
}

// 4. Client Code
public class OTTPlatformDemo {
    public static void main(String[] args) {
        VideoStreamer video = new VideoProxy();
        
        // Interaction starts with the Proxy
        video.playVideo("vid_101");
    }
}
