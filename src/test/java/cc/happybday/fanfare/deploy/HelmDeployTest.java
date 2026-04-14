package cc.happybday.fanfare.deploy;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.BuildImageResultCallback;
import com.marcnuri.helm.Helm;
import com.marcnuri.helm.Release;
import org.junit.jupiter.api.*;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.k3s.K3sContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

@Tag("deploy")
class HelmDeployTest {

    private static final String IMAGE_TAG = "test";
    private static final Path PROJECT_DIR = Paths.get("").toAbsolutePath();
    private static final Path CHART_DIR = PROJECT_DIR.resolve("helm/fan-fare-be");
    private static final Path VALUES_TEST = CHART_DIR.resolve("values-test.yaml");

    static K3sContainer k3s;
    static Path kubeconfigFile;

    @BeforeAll
    static void startK3sAndLoadImage() throws Exception {
        k3s = new K3sContainer(DockerImageName.parse("rancher/k3s:latest"));
        k3s.start();

        // Build Docker image
        String imageRef = "fan-fare-be/test:" + IMAGE_TAG;
        DockerClient docker = DockerClientFactory.instance().client();
        docker.buildImageCmd()
            .withDockerfile(PROJECT_DIR.resolve("Dockerfile").toFile())
            .withBaseDirectory(PROJECT_DIR.toFile())
            .withTags(Set.of(imageRef))
            .exec(new BuildImageResultCallback())
            .awaitImageId();

        // Save and load into K3s
        Path imageTar = Files.createTempFile("fan-fare-be", ".tar");
        try (InputStream is = docker.saveImageCmd(imageRef).exec()) {
            Files.copy(is, imageTar, StandardCopyOption.REPLACE_EXISTING);
        }
        k3s.copyFileToContainer(MountableFile.forHostPath(imageTar), "/tmp/image.tar");
        var importResult = k3s.execInContainer(
            "ctr", "-n", "k8s.io", "images", "import", "/tmp/image.tar"
        );
        if (importResult.getExitCode() != 0) {
            fail("Failed to import image: " + importResult.getStderr());
        }
        Files.deleteIfExists(imageTar);

        kubeconfigFile = Files.createTempFile("kubeconfig", ".yml");
        Files.writeString(kubeconfigFile, k3s.getKubeConfigYaml());
    }

    @AfterAll
    static void stopK3s() throws Exception {
        if (kubeconfigFile != null) Files.deleteIfExists(kubeconfigFile);
        if (k3s != null) k3s.stop();
    }

    @Test
    void deployAndHealthCheck() throws Exception {
        String kubeconfig = k3s.getKubeConfigYaml();

        // Install Helm chart and wait for readiness
        Release release = new Helm(CHART_DIR)
            .install()
            .withName("fan-fare-be")
            .withNamespace("default")
            .createNamespace()
            .withKubeConfigContents(kubeconfig)
            .withValuesFile(VALUES_TEST)
            .set("image.tag", IMAGE_TAG)
            .waitReady()
            .withTimeout(300)
            .call();

        assertEquals("deployed", release.getStatus());

        // Port-forward and health check
        Process portForward = new ProcessBuilder(
            "kubectl", "--kubeconfig", kubeconfigFile.toString(),
            "port-forward", "svc/fan-fare-be", "8080:8080"
        ).inheritIO().start();

        try {
            Thread.sleep(5000);
            HttpURLConnection conn = (HttpURLConnection)
                new URL("http://localhost:8080").openConnection();
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(10000);
            int status = conn.getResponseCode();
            assertTrue(status >= 200 && status < 500,
                "Health check failed, HTTP status: " + status);
        } finally {
            portForward.destroyForcibly();
        }
    }
}
