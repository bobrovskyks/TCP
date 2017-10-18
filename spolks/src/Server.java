import javafx.util.Pair;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousFileChannel;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.concurrent.Future;

public class Server {


    private static final int SERVER_PORT = 1337;
    private static final int THREAD_COUNT = 2;
    private ServerSocket server;
    private List<ConnectionThread> connections = new ArrayList<>();
    private List<Pair<File, String>> underUploadedFiles = new ArrayList<>();

    public Server() {
        try {
            server = new ServerSocket(SERVER_PORT);
            for(int i = 0; i < THREAD_COUNT; i++) {
                ConnectionThread thread = new ConnectionThread(this);
                connections.add(thread);
                new Thread(thread).start();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void listen() {
        if (server != null) {
            System.out.println("Server started");
            while (true) {
                try {
                    for(ConnectionThread connection : connections)
                        if (!connection.isWorked()) connection.startWork(server.accept());
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    private Pair<File, String> findUnderUploadedFileBy(String name) {
        for (Pair<File, String> underUploadedFile : underUploadedFiles)
            if (name.toUpperCase().equals(underUploadedFile.getKey().getName().toUpperCase())) return underUploadedFile;
        return null;
    }

    private static class ConnectionThread implements Runnable {

        private static final int BUFFER_SIZE = 64 * 1024;

        private volatile boolean isWorked = false;
        private Server server;
        private Socket connection;
        private InputStream input;
        private OutputStream output;

        public ConnectionThread(Server server) {
            this.server = server;
        }

        public void startWork(Socket connection) throws IOException {
            synchronized (this) {
                if (isWorked) return;
                isWorked = true;
                this.connection = connection;
                input = connection.getInputStream();
                output = connection.getOutputStream();
                System.out.println(connection.getRemoteSocketAddress() + " connected");
            }
        }

        public boolean isWorked() {
            return isWorked;
        }

        @Override
        public void run() {
            while (true) {
                synchronized (this) {
                    if (isWorked) {
                        try {
                            send("Connected to " + connection.getLocalSocketAddress());
                            BufferedReader inputReader = new BufferedReader(new InputStreamReader(input));
                            while (true) {
                                String line = inputReader.readLine();
                                if (line == null) break;
                                System.out.println(connection.getRemoteSocketAddress() + " >>> " + line);
                                process(line);
                            }
                        } catch (IOException ignored) {
                        } finally {
                            if (!connection.isClosed()) try {
                                connection.close();
                            } catch (IOException ignored) {
                            }
                            disconnect();
                        }
                    }
                }
            }
        }

        private void process(String line) throws IOException {
            line = line.trim();
            int delimiterIndex = line.indexOf(" ");
            String command = delimiterIndex == -1 ? line.toLowerCase()
                    : line.toLowerCase().substring(0, delimiterIndex).trim();
            String argument = delimiterIndex == -1 ? "" : line.substring(delimiterIndex).trim();
            switch (command) {
                case "echo":
                    send(argument);
                    break;
                case "time":
                    time();
                    break;
                case "close":
                    connection.close();
                    break;
                case "upload":
                    preupload(argument);
                    break;
                case "download":
                    download(argument);
                    break;
                default:
                    send("Wtf is " + command + "?");
            }
        }

        private void time() throws IOException {
            DateFormat dateFormat = new SimpleDateFormat("yyyy/MM/dd HH:mm:ss");
            Date date = new Date();
            send(dateFormat.format(date));
        }

        private void preupload(String argument) throws IOException {
            String fileName = argument.substring(0, argument.indexOf("|"));
            long fileSize = Long.parseLong(argument.substring(argument.indexOf("|") + 1));
            File file = new File("server " + fileName);
            if (!file.exists()) upload(file, 0, fileSize);
            else {
                Pair<File, String> desiredFile = server.findUnderUploadedFileBy(file.getName());
                if (desiredFile != null) {
                    String desiredFileIP = desiredFile.getValue().substring(0, desiredFile.getValue().indexOf(":"));
                    String currentClientIP = connection.getRemoteSocketAddress().toString()
                            .substring(0, connection.getRemoteSocketAddress().toString().indexOf(":"));
                    if (!desiredFileIP.equals(currentClientIP)) {
                        file.delete();
                        server.underUploadedFiles.remove(desiredFile);
                        upload(file, 0, fileSize);
                    } else {
                        upload(file, file.length(), fileSize);
                    }
                } else {
                    sendQuiet("-1");
                    send("File exists");
                }
            }
        }

        private void upload(File file, long offset, long fileSize) throws IOException {
            if (!file.exists()) {
                file.createNewFile();
                server.underUploadedFiles.add(new Pair<>(file, connection.getRemoteSocketAddress().toString()));
            }
            AsynchronousFileChannel fileChannel = AsynchronousFileChannel.open(
                    Paths.get(file.getPath()), StandardOpenOption.WRITE);
            List<Future> operations = new ArrayList<>();
            Date startTime = new Date();
            long initOffset = offset;
            sendQuiet(String.valueOf(offset));
            while (true) {
                if (offset >= fileSize) {
                    Pair<File, String> desiredFile = server.findUnderUploadedFileBy(file.getName());
                    if (desiredFile != null) {
                        String desiredFileIP = desiredFile.getValue().substring(0, desiredFile.getValue().indexOf(":"));
                        String currentClientIP = connection.getRemoteSocketAddress().toString()
                                .substring(0, connection.getRemoteSocketAddress().toString().indexOf(":"));
                        if (desiredFileIP.equals(currentClientIP)) {
                            server.underUploadedFiles.remove(desiredFile);
                            Date endTime = new Date();
                            double timeInSecs = (double)(endTime.getTime() - startTime.getTime()) / 1000D;
                            double mBits = (double)((fileSize - initOffset) * 8) / 1000000D;
                            double speed = timeInSecs == 0 ? Double.MAX_VALUE : mBits / timeInSecs;
                            send("File " + file.getName() + " was successfully uploaded. Total speed: "
                                    + speed + " Mbits");
                            while (true) {
                                boolean easyEnd = true;
                                for (Future operation : operations) if (!operation.isDone()) easyEnd = false;
                                if (easyEnd) break;
                            }
                            fileChannel.close();
                            break;
                        }
                    }
                }
                byte[] buffer = new byte[BUFFER_SIZE];
                int count = input.read(buffer);
                operations.add(fileChannel.write(ByteBuffer.wrap(Arrays.copyOfRange(buffer, 0, count)), offset));
                offset += count;
                System.out.println(connection.getRemoteSocketAddress() + " >>> byte[" + count + "] "
                        + ((double)offset / (double)fileSize) * 100 + "%");
            }
        }

        private void download(String filename) throws IOException {
            File file = new File(filename);
            if (!file.exists()) {
                send("No file");
                return;
            }
            send("File was found|" + file.length());
            BufferedReader inputReader = new BufferedReader(new InputStreamReader(input));
            RandomAccessFile fileReader = new RandomAccessFile(file, "r");
            String lineFromClient = inputReader.readLine();
            if (lineFromClient == null) throw new IOException();
            long uploadedBytes = Long.parseLong(lineFromClient);
            while (true) {
                fileReader.seek(uploadedBytes);
                byte[] bytes = new byte[BUFFER_SIZE];
                int countBytes = fileReader.read(bytes);
                if (countBytes <= 0) break;
                send(bytes, countBytes);
                uploadedBytes += countBytes;
                System.out.println(connection.getRemoteSocketAddress() + " <<< byte[" + countBytes + "]");
            }
        }

        private void send(String data) throws IOException {
            data += "\r\n";
            output.write(data.getBytes());
            output.flush();
            System.out.print(connection.getRemoteSocketAddress() + " <<< " + data);
        }

        private void send(byte[] data, int count) throws IOException {
            output.write(data, 0, count);
            output.flush();
        }

        private void sendQuiet(String data) throws IOException {
            data += "\r\n";
            output.write(data.getBytes());
            output.flush();
        }

        private void disconnect() {
            //server.connections.remove(this);
            isWorked = false;
            System.out.println(connection.getRemoteSocketAddress() + " disconnected");
        }
    }
}