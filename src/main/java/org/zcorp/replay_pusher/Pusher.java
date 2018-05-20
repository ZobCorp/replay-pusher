package org.zcorp.replay_pusher;

import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.StringUtils;
import org.glassfish.jersey.media.multipart.MultiPart;
import org.glassfish.jersey.media.multipart.MultiPartFeature;
import org.glassfish.jersey.media.multipart.file.FileDataBodyPart;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sun.management.FileSystem;

import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.Entity;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;

public class Pusher {

    private static final String HOST = "34.245.214.229";
    private static final String PORT = "8000";
    private static final Client client = ClientBuilder.newBuilder().register(MultiPartFeature.class).build();
    private static final WebTarget webTarget = client.target("http://" + HOST + ":" + PORT + "/player");
    private static final Logger logger = LoggerFactory.getLogger("org.zcorp.replay_pusher.Pusher");
    private static String parserPath;
    private static final String TEMP_DIR = System.getProperty("java.io.tmpdir");

    public static void main(String[] args) throws IOException {
        System.out.println(args);
        parserPath = args[0];
        if (parserPath == null) {
            throw new RuntimeException("Please specify parser's path as argument.");
        }
        if (!parserPath.endsWith(File.separator)) {
            parserPath += File.separator;
        }

        Path replayPaths = Paths.get(
                System.getProperty("user.home"),
                "Documents\\My Games\\Rocket League\\TAGame\\Demos");

        logger.info("Replays path: {}", replayPaths);
        logger.info("Start pushing replays");
        Files.list(replayPaths)
                .filter(replayPath -> !isAlreadyPushed(replayPath))
                .forEach(replayPath -> {
            try {
                Path jsonPath = parseReplay(replayPath);
                pushFile(jsonPath);
            } catch (Exception e) {
                logger.error("Error while handling replay {}", replayPath, e);
            }
        });
    }

    private static boolean isAlreadyPushed(Path replay) {
        logger.info("Checking if replay {} exists", replay.getFileName());
        Response response = webTarget
                .path("exists")
                .path(StringUtils.removeEnd(replay.getFileName().toString(), ".replay"))
                .request()
                .get();
        logger.info(response.getStatus() + " " + response.getStatusInfo() + " " + response);
        return response.getStatus() == 200;
    }

    private static void pushFile(Path path) throws Exception {
        File zipFile = zipFile(path);
        logger.info("Zip ({}): {}", FileUtils.byteCountToDisplaySize(zipFile.length()), zipFile);
        MultiPart multiPart = new MultiPart();

        FileDataBodyPart fileDataBodyPart = new FileDataBodyPart("files",
                zipFile, MediaType.APPLICATION_OCTET_STREAM_TYPE);
        multiPart.bodyPart(fileDataBodyPart);

        logger.info("Pushing: {}", path);
        Response response = webTarget
                .path("upload_no_redirect")
                .request(MediaType.MULTIPART_FORM_DATA)
                .post(Entity.entity(multiPart, multiPart.getMediaType()));

        if (response.getStatus() != 200) {
            throw new Exception("Error while posting replay: " + response.getStatus() + " " + response.getStatusInfo() + " " + response);
        }
    }

    private static Path parseReplay(Path replay) throws IOException, InterruptedException {
        logger.info("Parsing: {}", replay);
        Path jsonPath = Paths.get(TEMP_DIR, replay.getFileName() + ".json");
        logger.info("JSON output: {}", jsonPath);
        String parserExe = parserPath + "\\RocketLeagueReplayParser.exe";
        logger.info("parserExe: {}", parserExe);
        String command = parserExe + " " + replay.toString() + " > " + jsonPath.toString();
        logger.info("Command: {}", command);
        ProcessBuilder processBuilder = new ProcessBuilder(parserPath + "RocketLeagueReplayParser.exe", "\"" + replay.toString() + "\"", "--fileoutput");
        processBuilder.directory(new File(parserPath));
        Process process = processBuilder.start();
        logger.info("Wait for parsing");
        int exitCode = process.waitFor();
        logger.info("Parsing exited with code {}", exitCode);
        Path output = Paths.get(StringUtils.removeEnd(parserPath + replay.getFileName(), ".replay") + ".json");
        Files.move(output, jsonPath, REPLACE_EXISTING);
        return jsonPath;
    }

    private static File zipFile(Path baseFilePath) throws IOException {
        String zipName = TEMP_DIR + baseFilePath.getFileName() + ".zip";
        File targetFile = baseFilePath.toFile();

        ZipOutputStream zipOutputStream = null;
        FileInputStream inputStream = null;
        try {
            zipOutputStream = new ZipOutputStream(new FileOutputStream(zipName));
            zipOutputStream.putNextEntry(new ZipEntry(targetFile.getName()));
            inputStream = new FileInputStream(targetFile);

            final byte[] buffer = new byte[1024];
            int length;
            while ((length = inputStream.read(buffer)) >= 0) {
                zipOutputStream.write(buffer, 0, length);
            }
        } finally {
            if (zipOutputStream != null) {
                zipOutputStream.close();
            }
            if (inputStream != null) {
                inputStream.close();
            }
        }
        return new File(zipName);
    }
}
