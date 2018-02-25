package com.github.jborza.camel.component.smbj;

import com.hierynomus.msdtyp.AccessMask;
import com.hierynomus.msfscc.fileinformation.FileIdBothDirectoryInformation;
import com.hierynomus.mssmb2.SMB2CreateDisposition;
import com.hierynomus.mssmb2.SMB2ShareAccess;
import com.hierynomus.smbj.SMBClient;
import com.hierynomus.smbj.auth.AuthenticationContext;
import com.hierynomus.smbj.connection.Connection;
import com.hierynomus.smbj.session.Session;
import com.hierynomus.smbj.share.DiskShare;
import com.hierynomus.smbj.share.File;
import org.apache.camel.Exchange;
import org.apache.camel.component.file.*;
import org.apache.camel.util.IOHelper;
import org.apache.camel.util.ObjectHelper;

import java.io.*;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

import static com.github.jborza.camel.component.smbj.SmbConstants.CURRENT_DIRECTORY;
import static com.github.jborza.camel.component.smbj.SmbConstants.PARENT_DIRECTORY;
import static com.hierynomus.mssmb2.SMB2CreateDisposition.FILE_CREATE;

public class SmbOperations implements GenericFileOperations<SmbFile> {
    public static final int DEFAULT_COPY_BUFFER_SIZE = 4096;
    private final SMBClient client;
    private Session session;
    private GenericFileEndpoint<SmbFile> endpoint;
    private static final int MAX_BUFFER_SIZE = 262144;

    public SmbOperations(SMBClient client) {
        this.client = client;
    }

    @Override
    public void setEndpoint(GenericFileEndpoint<SmbFile> genericFileEndpoint) {
        this.endpoint = genericFileEndpoint;
    }

    @Override
    public boolean deleteFile(String name) throws GenericFileOperationFailedException {
        return false;
    }

    @Override
    public boolean existsFile(String name) throws GenericFileOperationFailedException {
        //TODO test
        login();
        SmbConfiguration config = ((SmbConfiguration) endpoint.getConfiguration());
        DiskShare share = (DiskShare) session.connectShare(config.getShare());
        return share.fileExists(name);
    }

    @Override
    public boolean renameFile(String s, String s1) throws GenericFileOperationFailedException {
        return false;
    }

    @Override
    public boolean buildDirectory(String directory, boolean absolute) throws GenericFileOperationFailedException {
        login();
        SmbConfiguration config = ((SmbConfiguration) endpoint.getConfiguration());

        DiskShare share = (DiskShare) session.connectShare(config.getShare());

        //strip share name from the beginning of directory
        String shareName = config.getShare();
        String directoryNormalized = directory.replaceFirst("^" + shareName, "");

        Path path = Paths.get(directoryNormalized);
        int len = path.getNameCount();
        for (int i = 0; i < len; i++) {
            Path partialPath = path.subpath(0, i + 1);
            String pathAsString = SmbPathUtils.convertToBackslashes(partialPath.toString());
            boolean exists = share.folderExists(pathAsString);
            if (exists == false)
                share.mkdir(pathAsString);
        }

        return true;
    }

    public static int copy(InputStream input, OutputStream output) throws IOException {
        return copy(input, output, DEFAULT_COPY_BUFFER_SIZE);
    }

    public static int copy(InputStream input, OutputStream output, int bufferSize) throws IOException {
        return copy(input, output, bufferSize, false);
    }

    public static int copy(InputStream input, OutputStream output, int bufferSize, boolean flushOnEachWrite) throws IOException {
        if (input instanceof ByteArrayInputStream) {
            input.mark(0);
            input.reset();
            bufferSize = input.available();
        } else {
            int avail = input.available();
            if (avail > bufferSize) {
                bufferSize = avail;
            }
        }

        if (bufferSize > MAX_BUFFER_SIZE) {
            bufferSize = MAX_BUFFER_SIZE;
        }

        byte[] buffer = new byte[bufferSize];
        int n = input.read(buffer);

        int total;
        for (total = 0; -1 != n; n = input.read(buffer)) {
            output.write(buffer, 0, n);
            if (flushOnEachWrite) {
                output.flush();
            }

            total += n;
        }

        if (!flushOnEachWrite) {
            output.flush();
        }

        return total;
    }

    @Override
    public boolean retrieveFile(String name, Exchange exchange) throws GenericFileOperationFailedException {
        OutputStream os = null;
        try {
            os = new ByteArrayOutputStream();
            GenericFile<SmbFile> target = (GenericFile<SmbFile>) exchange.getProperty(FileComponent.FILE_EXCHANGE_FILE);
            ObjectHelper.notNull(target, "Exchange should have the " + FileComponent.FILE_EXCHANGE_FILE + " set");
            target.setBody(os);

            login();
            SmbConfiguration config = ((SmbConfiguration) endpoint.getConfiguration());

            DiskShare share = (DiskShare) session.connectShare(config.getShare());
            String path = name;
            path = SmbPathUtils.convertToBackslashes(path);
            //strip share name from path
            path = SmbPathUtils.removeShareName(path, config.getShare(), true);

            File f = share.openFile(path, EnumSet.of(AccessMask.GENERIC_READ), null, SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OPEN, null);
            InputStream is = f.getInputStream();

            copy(is, os, endpoint.getBufferSize());
            return true;
        } catch (IOException e) {
            throw new GenericFileOperationFailedException("Cannot retrieve file: " + name, e);
        } catch (Exception e) {
            throw new GenericFileOperationFailedException("Cannot retrieve file: " + name, e);
        } finally {
            IOHelper.close(os, "retrieve: " + name);
        }
    }

    @Override
    public void releaseRetreivedFileResources(Exchange exchange) throws GenericFileOperationFailedException {

    }

    @Override
    public String getCurrentDirectory() throws GenericFileOperationFailedException {
        return null;
    }

    @Override
    public void changeCurrentDirectory(String path) throws GenericFileOperationFailedException {

    }

    @Override
    public void changeToParentDirectory() throws GenericFileOperationFailedException {

    }

    @Override
    public List<SmbFile> listFiles() throws GenericFileOperationFailedException {
        return null;
    }

    @Override
    public List<SmbFile> listFiles(String path) throws GenericFileOperationFailedException {
        String actualPath;
        List<SmbFile> files = new ArrayList<>();
        try {
            login();
            SmbConfiguration config = ((SmbConfiguration) endpoint.getConfiguration());
            DiskShare share = (DiskShare) session.connectShare(config.getShare());
            actualPath = SmbPathUtils.convertToBackslashes(path);
            //strip share name from path
            actualPath = SmbPathUtils.removeShareName(actualPath, config.getShare(), true);
            for (FileIdBothDirectoryInformation f : share.list(actualPath)) {
                boolean isDirectory = isDirectory(f);
                if (isDirectory) {
                    //skip special directories . and ..
                    if (f.getFileName().equals(CURRENT_DIRECTORY) || f.getFileName().equals(PARENT_DIRECTORY))
                        continue;
                }
                files.add(new SmbFile(isDirectory, f.getFileName(), f.getEndOfFile(), getLastModified(f)));
            }
        } catch (Exception e) {
            throw new GenericFileOperationFailedException("Could not get files " + e.getMessage(), e);
        }
        return files;
    }

    private static boolean isDirectory(FileIdBothDirectoryInformation info) {
        return (info.getFileAttributes() & SmbConstants.FILE_ATTRIBUTE_DIRECTORY) == SmbConstants.FILE_ATTRIBUTE_DIRECTORY;
    }

    private static long getLastModified(FileIdBothDirectoryInformation info) {
        return info.getLastWriteTime().toEpochMillis();
    }

    private String getPath(String pathEnd) {
        String path = ((SmbConfiguration) endpoint.getConfiguration()).getSmbHostPath() + pathEnd;
        return path.replace('\\', '/');
    }

    public void login() {
        SmbConfiguration config = ((SmbConfiguration) endpoint.getConfiguration());

        String domain = config.getDomain();
        String username = config.getUsername();
        String password = config.getPassword();

        if (session != null) {
            return;
        }
        try {
            Connection connection = client.connect(config.getHost());
            session = connection.authenticate(new AuthenticationContext(username, password.toCharArray(), domain));
        } catch (IOException e) {
            //TODO what now?
        }
    }

    @Override
    public boolean storeFile(String name, Exchange exchange) {
        String storeName = getPath(name);

        InputStream inputStream = null;
        try {
            inputStream = exchange.getIn().getMandatoryBody(InputStream.class);

            login();
            SmbConfiguration config = ((SmbConfiguration) endpoint.getConfiguration());

            DiskShare share = (DiskShare) session.connectShare(config.getShare());
            GenericFile<SmbFile> inputFile = (GenericFile<SmbFile>) exchange.getIn().getBody();
            Path path = Paths.get(config.getPath(), inputFile.getRelativeFilePath());
            String pathAsString = SmbPathUtils.convertToBackslashes(path.toString());
            File file = share.openFile(pathAsString, EnumSet.of(AccessMask.GENERIC_WRITE), null, SMB2ShareAccess.ALL, FILE_CREATE, null);

            OutputStream smbout = file.getOutputStream();
            byte[] buf = new byte[512 * 1024]; //TODO this should be parametrizable
            int numRead;
            while ((numRead = inputStream.read(buf)) >= 0) {
                smbout.write(buf, 0, numRead);
            }
            smbout.close();
            //TODO set last modified date to inputFile.getLastModified()
            return true;
        } catch (Exception e) {
            throw new GenericFileOperationFailedException("Cannot store file " + storeName, e);
        } finally {
            IOHelper.close(inputStream, "store: " + storeName);
        }
    }
}