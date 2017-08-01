/*
  Evernote API sample code, structured as a simple command line
  application that demonstrates several API calls.
  
  To compile (Unix):
    javac -classpath ../../target/evernote-api-*.jar EDAMDemo.java
 
  To run:
    java -classpath ../../target/evernote-api-*.jar EDAMDemo
 
  Full documentation of the Evernote API can be found at 
  http://dev.evernote.com/documentation/cloud/
 */

import com.evernote.auth.EvernoteAuth;
import com.evernote.auth.EvernoteService;
import com.evernote.clients.ClientFactory;
import com.evernote.clients.NoteStoreClient;
import com.evernote.clients.UserStoreClient;
import com.evernote.edam.error.EDAMErrorCode;
import com.evernote.edam.error.EDAMSystemException;
import com.evernote.edam.error.EDAMUserException;
import com.evernote.edam.type.*;
import com.evernote.thrift.transport.TTransportException;
import org.apache.commons.io.FilenameUtils;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class BulkUploader {

    private UserStoreClient userStore;
    private NoteStoreClient noteStore;
    private String newNoteGuid;

    public static void main(String args[]) throws Exception {

        if (args.length == 0) {
            System.err.println("Please provide a file or directory name paraneter.");
            return;
        }

        String token = System.getenv("AUTH_TOKEN");

        if (token == null) {
            System.err.println("Please fill in your developer token in environment variable AUTH_TOKEN");
            return;
        }

        BulkUploader uploader = new BulkUploader(token);
        try {
            for (String noteName : args) {
                uploader.createNote(noteName);
            }

            uploader.updateNoteTag();
        } catch (EDAMUserException e) {
            // These are the most common error types that you'll need to
            // handle
            // EDAMUserException is thrown when an API call fails because a
            // parameter was invalid.
            if (e.getErrorCode() == EDAMErrorCode.AUTH_EXPIRED) {
                System.err.println("Your authentication token is expired!");
            } else if (e.getErrorCode() == EDAMErrorCode.INVALID_AUTH) {
                System.err.println("Your authentication token is invalid!");
            } else if (e.getErrorCode() == EDAMErrorCode.QUOTA_REACHED) {
                System.err.println("Your authentication token is invalid!");
            } else {
                System.err.println("Error: " + e.getErrorCode().toString()
                        + " parameter: " + e.getParameter());
            }
        } catch (EDAMSystemException e) {
            System.err.println("System error: " + e.getErrorCode().toString());
        } catch (TTransportException t) {
            System.err.println("Networking error: " + t.getMessage());
        }
    }

    /**
     * Intialize UserStore and NoteStore clients. During this step, we
     * authenticate with the Evernote web service. All of this code is boilerplate
     * - you can copy it straight into your application.
     */
    private BulkUploader(String token) throws Exception {
        // Set up the UserStore client and check that we can speak to the server
        EvernoteAuth evernoteAuth = new EvernoteAuth(EvernoteService.SANDBOX, token);
        ClientFactory factory = new ClientFactory(evernoteAuth);
        userStore = factory.createUserStoreClient();

        boolean versionOk = userStore.checkVersion("Evernote EDAMDemo (Java)",
                com.evernote.edam.userstore.Constants.EDAM_VERSION_MAJOR,
                com.evernote.edam.userstore.Constants.EDAM_VERSION_MINOR);
        if (!versionOk) {
            System.err.println("Incompatible Evernote client protocol version");
            System.exit(1);
        }

        // Set up the NoteStore client
        noteStore = factory.createNoteStoreClient();
    }

    /**
     * Create a new note containing a little text and the Evernote icon.
     */
    private void createNote(String filename) throws Exception {
        Note note = new Note();
        String noteTitle = filenameToTitle(filename);
        note.setTitle(noteTitle);

        addContent(note, filename);

        Note createdNote = noteStore.createNote(note);
        newNoteGuid = createdNote.getGuid();

        System.out.println("New note " + note.getTitle() + " has GUID " + newNoteGuid);
        System.out.println();
    }

    private void addContent(Note note, String filename) throws Exception {

        StringBuilder contentBuilder = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<!DOCTYPE en-note SYSTEM \"http://xml.evernote.com/pub/enml2.dtd\">"
                + "<en-note>");
        for (String attachment : getFiles(filename)) {
            String mimeType = filenameToMimeType(attachment);

            Resource resource = new Resource();
            resource.setData(readFileAsData(attachment));
            resource.setMime(mimeType);
            ResourceAttributes attributes = new ResourceAttributes();
            attributes.setFileName(attachment);
            resource.setAttributes(attributes);

            note.addToResources(resource);

            String hashHex = bytesToHex(resource.getData().getBodyHash());

            contentBuilder.append("<en-media type=\"").append(mimeType).append("\" hash=\"").append(hashHex).append("\"/>");
            System.out.println("Adding attachment " + attachment);
        }
        String content = contentBuilder.toString();

        content += "</en-note>";
        note.setContent(content);
    }

    /**
     * Get the files to add to a note: if we start out with a file,
     * then return just that file; if we start out with a directory, then
     * return all of the regular files directly contained by the directory.
     */
    private static List<String> getFiles(String source) {
        List<String> files = new ArrayList<>();

        if (new File(source).isDirectory()) {
            File directory = new File(source);
            File[] listOfFiles = directory.listFiles();

            if (listOfFiles != null) {
                Arrays.sort(listOfFiles);

                for (File file : listOfFiles) {
                    if (file.isFile() && ! file.getName().matches("\\..*")) {
                        files.add(file.getAbsolutePath());
                    }
                }
            }
        }
        else {
            files.add(source);
        }

    return files;
    }

    /**
     * Update the tags assigned to a note. This method demonstrates how only
     * modified fields need to be sent in calls to updateNote.
     */
    private void updateNoteTag() throws Exception {
        Note note = noteStore.getNote(newNoteGuid, true, true, false, false);

        note.unsetContent();
        note.unsetResources();
        note.addToTagNames("BulkUploader");
        noteStore.updateNote(note);
        System.out.println("Successfully added tag to existing note");

        // To prove that we didn't destroy the note, let's fetch it again and
        // verify that it still has 1 resource.
        note = noteStore.getNote(newNoteGuid, false, false, false, false);
        System.out.println("After update, note has " + note.getResourcesSize()
                + " resource(s)");
        System.out.println("After update, note tags are: ");
        for (String tagGuid : note.getTagGuids()) {
            Tag tag = noteStore.getTag(tagGuid);
            System.out.println("* " + tag.getName());
        }

        System.out.println();
    }

    /**
     * Helper method to read the contents of a file on disk and create a new Data
     * object.
     */
    private static Data readFileAsData(String filePath) throws Exception {

        // Read the full binary contents of the file
        FileInputStream in = new FileInputStream(filePath);
        ByteArrayOutputStream byteOut = new ByteArrayOutputStream();
        byte[] block = new byte[10240];
        int len;
        while ((len = in.read(block)) >= 0) {
            byteOut.write(block, 0, len);
        }
        in.close();
        byte[] body = byteOut.toByteArray();

        // Create a new Data object to contain the file contents
        Data data = new Data();
        data.setSize(body.length);
        data.setBodyHash(MessageDigest.getInstance("MD5").digest(body));
        data.setBody(body);

        return data;
    }

    /**
     * Helper method to convert a byte array to a hexadecimal string.
     */
    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte hashByte : bytes) {
            int intVal = 0xff & hashByte;
            if (intVal < 0x10) {
                sb.append('0');
            }
            sb.append(Integer.toHexString(intVal));
        }
        return sb.toString();
    }

    /**
     *
     * Helper method to turn a filename into a slightly more
     * human-readable string.
     */

    private String filenameToTitle(String filename) {
        return FilenameUtils.getBaseName(filename);
    }

    /**
     *
     * Helper method to turn a filename into a MIME type.
     * Not complete, but sufficient for local purposes.
     */

    private String filenameToMimeType(String filename) {
        if (filename.matches("^.*jpg") || filename.matches("^.*.jpeg")) {
            return "image/jpg";
        }
        else if (filename.matches("^.*.png")) {
            return "image/png";
        }
        else if (filename.matches("^.*.pdf")) {
            return "application/pdf";
        }
        else {
            return null;
        }
    }
}