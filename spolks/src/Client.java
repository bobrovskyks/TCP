import javafx.util.Pair;

import java.io.*;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousFileChannel;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.concurrent.Future;

public class Client {

    private static final int BUFFER_SIZE = 64 * 1024;
    private static final int SERVER_PORT = 1337;

    private Socket connection;
    private InputStream input;
    private OutputStream output;
    private BufferedReader user = new BufferedReader(new InputStreamReader(System.in));
    private List<Pair<File, String>> underDownloadedFiles = new ArrayList<>();

    public void start() {
        while (true) {
            try {
                System.out.println("To connect type 'c', to quit type 'q'");
                String line;
                line = user.readLine();
                if (line.equals("")) continue;
                if (line.charAt(0) == 'c') setupConnection();
                if (line.charAt(0) == 'q') break;
            } catch (IOException | NumberFormatException ignored) {
            }
        }
    }

    private void setupConnection() throws IOException {
        System.out.println("Server IP?");
        String ip = user.readLine();
        connect(ip, SERVER_PORT);
    }

    private void connect(String ip, Integer port) {
        try {
            connection = new Socket(ip, port);
            input = connection.getInputStream();
            output = connection.getOutputStream();
            listen();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void listen() {
        try {
            BufferedReader inputReader = new BufferedReader(new InputStreamReader(input));
            while (true) {
                String lineFromServer = inputReader.readLine();
                if (lineFromServer == null) throw new IOException();
                System.out.println(">>> " + lineFromServer);
                while (true) {
                    String lineFromUser = user.readLine();
                    if (process(lineFromUser)) break;
                }
            }
        } catch (IOException ignored) {
        }
        disconnect();
    }

    private boolean process(String line) throws IOException {
        line = line.trim();
        int delimiterIndex = line.indexOf(" ");
        String command = delimiterIndex == -1 ? line.toLowerCase()
                : line.toLowerCase().substring(0, delimiterIndex).trim();
        String argument = delimiterIndex == -1 ? "" : line.substring(delimiterIndex).trim();
        switch (command) {
            case "upload":
                return upload(argument, line);
            case "download":
                predownload(argument, line);
                return false;
            default:
                send(line);
        }
        return true;
    }

    private boolean upload(String filename, String command) throws IOException {
        File file = new File(filename);
        if (!file.exists()) {
            System.out.println("No file");
            return false;
        }
        send(command + "|" + file.length());
        BufferedReader inputReader = new BufferedReader(new InputStreamReader(input));
        RandomAccessFile fileReader = new RandomAccessFile(file, "r");
        String lineFromServer = inputReader.readLine();
        if (lineFromServer == null) throw new IOException();
        long uploadedBytes = Long.parseLong(lineFromServer);
        if (uploadedBytes == -1) return true;
        while (true) {
            fileReader.seek(uploadedBytes);
            byte[] bytes = new byte[BUFFER_SIZE];
            int countBytes = fileReader.read(bytes);
            if (countBytes <= 0) break;
            send(bytes, countBytes);
            uploadedBytes += countBytes;
            System.out.println("<<< byte[" + countBytes + "]");
        }
        return true;
    }

    private void predownload(String filename, String command) throws IOException {
        File file = new File("client " + filename);
        if (file.exists()) {
            Pair<File, String> desiredFile = findUnderDownloadedFileBy(file.getName());
            if (desiredFile != null) {
                Long fileSize = checkFileOnRemote(command);
                if (fileSize == null) return;
                if (desiredFile.getValue().equals(connection.getRemoteSocketAddress().toString())) {
                    download(file, file.length(), fileSize);
                } else {
                    file.delete();
                    underDownloadedFiles.remove(desiredFile);
                    download(file, 0, fileSize);
                }
            } else {
                System.out.println("File already exists");
            }
        } else {
            Long fileSize = checkFileOnRemote(command);
            if (fileSize == null) return;
            download(file, 0, fileSize);
        }
    }

    private Long checkFileOnRemote(String command) throws IOException {
        send(command);
        BufferedReader inputReader = new BufferedReader(new InputStreamReader(input));
        String line = inputReader.readLine();
        if (line == null) throw new IOException();
        System.out.println(">>> " + line);
        if (line.trim().equals("No file")) {
            return null;
        } else {
            return Long.parseLong(line.trim().substring(line.indexOf("|") + 1));
        }
    }

    private void download(File file, long offset, long fileSize) throws IOException {
        if (!file.exists()) {
            file.createNewFile();
            underDownloadedFiles.add(new Pair<>(file, connection.getRemoteSocketAddress().toString()));
        }
        AsynchronousFileChannel fileChannel = AsynchronousFileChannel.open(
                Paths.get(file.getPath()), StandardOpenOption.WRITE);
        List<Future> operations = new ArrayList<>();
        Date startTime = new Date();
        long initOffset = offset;
        sendQuiet(String.valueOf(offset));
        while (true) {
            if (offset >= fileSize) {
                Pair<File, String> desiredFile = findUnderDownloadedFileBy(file.getName());
                if (desiredFile != null
                        && desiredFile.getValue().equals(connection.getRemoteSocketAddress().toString())) {
                    underDownloadedFiles.remove(desiredFile);
                    Date endTime = new Date();
                    double timeInSecs = (double)(endTime.getTime() - startTime.getTime()) / 1000D;
                    double mBits = (double)((fileSize - initOffset) * 8) / 1000000D;
                    double speed = timeInSecs == 0 ? Double.MAX_VALUE : mBits / timeInSecs;
                    System.out.println("File was downloaded. Total speed: " + speed + " Mbits");
                    while (true) {
                        boolean easyEnd = true;
                        for (Future operation : operations) if (!operation.isDone()) easyEnd = false;
                        if (easyEnd) break;
                    }
                    fileChannel.close();
                    break;
                }
            }
            byte[] buffer = new byte[BUFFER_SIZE];
            int count = input.read(buffer);
            operations.add(fileChannel.write(ByteBuffer.wrap(Arrays.copyOfRange(buffer, 0, count)), offset));
            offset += count;
            System.out.println(">>> byte[" + count + "] " + ((double)offset / (double)fileSize) * 100 + "%");
        }
    }

    private Pair<File, String> findUnderDownloadedFileBy(String name) {
        for (Pair<File, String> underDownloadedFile : underDownloadedFiles)
            if (name.toUpperCase().equals(underDownloadedFile.getKey().getName().toUpperCase()))
                return underDownloadedFile;
        return null;
    }

    private void sendQuiet(String data) throws IOException {
        data += "\r\n";
        output.write(data.getBytes());
        output.flush();
    }

    private void send(String data) throws IOException {
        data += "\r\n";
        output.write(data.getBytes());
        output.flush();
        System.out.print("<<< " + data);
    }

    private void send(byte[] data, int count) throws IOException {
        output.write(data, 0, count);
        output.flush();
    }

    private void disconnect() {
        System.out.println(connection.getRemoteSocketAddress() + " disconnected");
    }
}
