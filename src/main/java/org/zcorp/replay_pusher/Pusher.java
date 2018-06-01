package org.zcorp.replay_pusher;

import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.Response;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.comparator.LastModifiedFileComparator;
import org.apache.commons.lang3.StringUtils;
import org.apache.http.HttpEntity;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.ResponseHandler;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.client.methods.RequestBuilder;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.mime.HttpMultipartMode;
import org.apache.http.entity.mime.MultipartEntityBuilder;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.glassfish.jersey.media.multipart.MultiPart;
import org.glassfish.jersey.media.multipart.MultiPartFeature;
import org.glassfish.jersey.media.multipart.file.FileDataBodyPart;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Pusher {

	private static final String HOST = "34.248.29.223";
	//private static final String HOST = "localhost";
	private static final String PORT = "8000";
	private static final Client client = ClientBuilder.newBuilder().register(MultiPartFeature.class).build();
	private static final WebTarget webTarget = client.target("http://" + HOST + ":" + PORT + "/player");
	private static final Logger logger = LoggerFactory.getLogger("org.zcorp.replay_pusher.Pusher");
	private static String parserPath;
	private static final String TEMP_DIR = System.getProperty("java.io.tmpdir");

	public static void main(String[] args) throws IOException {

		final boolean parseReplay;
		if (args.length == 0) {
			parseReplay = false;
		} else {
 			System.out.println(args);
			parserPath = args[0];
			if (!parserPath.endsWith(File.separator)) {
				parserPath += File.separator;
			}
			parseReplay = true;
		}

		Path replayPaths = Paths.get(System.getProperty("user.home"),
				"Documents\\My Games\\Rocket League\\TAGame\\Demos");

		logger.info("Replays path: {}", replayPaths);
		logger.info("Start pushing replays");
		File[] files = replayPaths.toFile().listFiles();
		Arrays.asList(files).stream().sorted(LastModifiedFileComparator.LASTMODIFIED_REVERSE)
				.filter(file -> !isAlreadyPushed(file.toPath())).forEach(file -> {
					try {
						if (parseReplay) {
							Path jsonPath = parseReplay(file.toPath());
							pushFile(jsonPath, "upload_no_redirect", true);
						} else {
							pushFile(file.toPath(), "upload_replay_no_redirect", false);
						}
					} catch (Exception e) {
						logger.error("Error while handling replay {}", file, e);
					}
				});
	}

	private static boolean isAlreadyPushed(Path replay) {
		logger.info("Checking if replay {} exists", replay.getFileName());
		Response response = webTarget.path("exists")
				.path(StringUtils.removeEnd(replay.getFileName().toString(), ".replay")).request().get();
		logger.info(response.getStatus() + " " + response.getStatusInfo() + " " + response);
		return response.getStatus() == 200;
	}

	private static void pushFile(Path path, String endpoint, boolean zipBeforeSend) throws Exception {

        CloseableHttpClient httpclient = HttpClients.createDefault();
        File file;
        if(zipBeforeSend) {
            file = zipFile(path);	
        } else {
            file = path.toFile();
        }
        
        logger.info("Zip ({}): {}", FileUtils.byteCountToDisplaySize(file.length()), file);
        MultiPart multiPart = new MultiPart();

        FileDataBodyPart fileDataBodyPart = new FileDataBodyPart("files", file);
        multiPart.bodyPart(fileDataBodyPart);

        logger.info("Pushing: {}", path);
        
        HttpEntity data = MultipartEntityBuilder.create()
                .setMode(HttpMultipartMode.BROWSER_COMPATIBLE)
                .addBinaryBody("upfile", file, ContentType.DEFAULT_BINARY, file.getName())
                .addTextBody("filename", path.getFileName().toString(), ContentType.DEFAULT_BINARY)
                .build();
        
        HttpUriRequest request = RequestBuilder
                .post("http://" + HOST + ":" + PORT + "/player/"+endpoint)
                .setEntity(data)
                .build();

        // Create a custom response handler
        ResponseHandler<String> responseHandler = response -> {
            int status = response.getStatusLine().getStatusCode();
            if (status != 200) {
                throw new ClientProtocolException("Error while posting replay: " + status + " " + response.getStatusLine().getReasonPhrase() + " " + response);
            }
            return "";
        };
        String responseBody = httpclient.execute(request, responseHandler);
        System.out.println(responseBody);
    }

	private static Path parseReplay(Path replay) throws IOException, InterruptedException {
		logger.info("Parsing: {}", replay);
		String matchId = StringUtils.removeEnd(replay.getFileName().toString(), ".replay");
		Path jsonPath = Paths.get(TEMP_DIR, matchId + ".json");
		logger.info("JSON output: {}", jsonPath);
		String parserExe = parserPath + "\\RocketLeagueReplayParser.exe";
		logger.info("parserExe: {}", parserExe);
		String command = parserExe + " " + replay.toString() + " > " + jsonPath.toString();
		logger.info("Command: {}", command);
		ProcessBuilder processBuilder = new ProcessBuilder(parserPath + "RocketLeagueReplayParser.exe",
				"\"" + replay.toString() + "\"", "--fileoutput");
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
