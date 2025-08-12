import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.framework.recipes.leader.LeaderLatch;
import org.apache.curator.framework.recipes.locks.InterProcessMutex;
import org.apache.curator.retry.ExponentialBackoffRetry;
import org.apache.zookeeper.KeeperException;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class CinemaDistribuido {

    // --- Configurações ---
    private static final String ZK_HOSTS = "127.0.0.1:2181";
    private static final String SESSION_ID = "sessao_filme_" + new Random().nextInt(1000);
    private static final int TOTAL_SEATS = 10;
    private static final List<String> SEATS_TO_BOOK_IN_PARALLEL = Arrays.asList("F7", "F7", "A1", "C4", "B2", "C4");

    private final CuratorFramework client;
    private final String sessionPath;
    private final String seatsPath;
    private final String locksPath;
    private final String leaderPath;

    /**
     * Construtor que inicializa a conexão com o Zookeeper e cria a estrutura de nós.
     * @param zkHosts String de conexão do Zookeeper.
     * @param sessionId ID único para a sessão de cinema.
     */
    public CinemaDistribuido(String zkHosts, String sessionId) {
        System.out.println("Iniciando sistema para a sessão: " + sessionId);
        this.sessionPath = "/cinema/" + sessionId;
        this.seatsPath = this.sessionPath + "/seats";
        this.locksPath = this.sessionPath + "/locks";
        this.leaderPath = this.sessionPath + "/leader";

        // Conecta-se ao Zookeeper com uma política de novas tentativas em caso de falha.
        this.client = CuratorFrameworkFactory.newClient(zkHosts, new ExponentialBackoffRetry(1000, 3));
        this.client.start();

        // Garante que a estrutura de nós base exista no Zookeeper.
        try {
            System.out.println("Garantindo a estrutura de nós no Zookeeper...");
            // O creatingParentsIfNeeded() é útil para garantir toda a árvore de diretórios.
            if (client.checkExists().forPath(this.sessionPath) == null) {
                 client.create().creatingParentsIfNeeded().forPath(this.leaderPath);
                 client.create().forPath(this.seatsPath);
                 client.create().forPath(this.locksPath);
            }
        } catch (KeeperException.NodeExistsException e) {
            // Ignora se os nós já existem, o que é esperado em execuções subsequentes.
            System.out.println("Estrutura de nós já existe. Pulando criação.");
        } catch (Exception e) {
            throw new RuntimeException("Falha ao criar estrutura no Zookeeper", e);
        }
    }

    /**
     * Fecha a conexão com o Zookeeper.
     */
    public void close() {
        this.client.close();
    }

    /**
     * Cria os znodes para cada assento, inicializando-os como 'DISPONIVEL'.
     * @param totalSeats Número total de assentos a serem criados.
     */
    public void setupSeats(int totalSeats) throws Exception {
        System.out.println("\n--- CONFIGURANDO " + totalSeats + " ASSENTOS ---");
        for (int i = 1; i <= totalSeats; i++) {
            String seatId = "F" + i;
            String seatPath = this.seatsPath + "/" + seatId;
            if (client.checkExists().forPath(seatPath) == null) {
                client.create().forPath(seatPath, "DISPONIVEL".getBytes());
            }
        }
        System.out.println("Assentos configurados com sucesso.");
    }

    /**
     * Retorna um mapa com o status de todos os assentos.
     * @return um Map<String, String> com o ID do assento e seu status.
     */
    public Map<String, String> getAllSeatsStatus() throws Exception {
        Map<String, String> statuses = new TreeMap<>(); // TreeMap para ordenar as chaves (assentos)
        List<String> seatIds = client.getChildren().forPath(this.seatsPath);
        for (String seatId : seatIds) {
            byte[] data = client.getData().forPath(this.seatsPath + "/" + seatId);
            statuses.put(seatId, new String(data));
        }
        return statuses;
    }

    /**
     * Tenta reservar um assento usando um Lock distribuído para garantir exclusividade.
     * @param seatId O ID do assento a ser reservado.
     * @param userId O ID do usuário que está tentando a reserva.
     */
    public void bookSeat(String seatId, String userId) {
        String messagePrefix = "[" + userId + "]";
        System.out.println(messagePrefix + " está tentando reservar o assento " + seatId + "...");
        String lockPath = this.locksPath + "/" + seatId;

        // Cria um lock mutex distribuído para o assento específico.
        InterProcessMutex lock = new InterProcessMutex(this.client, lockPath);

        try {
            // Tenta adquirir o lock com um timeout de 5 segundos.
            if (lock.acquire(5, TimeUnit.SECONDS)) {
                System.out.println(messagePrefix + " adquiriu o LOCK para o assento " + seatId + ".");
                // --- SEÇÃO CRÍTICA ---
                try {
                    String seatPath = this.seatsPath + "/" + seatId;
                    byte[] data = client.getData().forPath(seatPath);
                    String status = new String(data);

                    if ("DISPONIVEL".equals(status)) {
                        System.out.println(messagePrefix + " confirmou que " + seatId + " está disponível. Reservando...");
                        client.setData().forPath(seatPath, ("RESERVADO_POR_" + userId).getBytes());
                        System.out.println("✅ " + messagePrefix + " reservou com SUCESSO o assento " + seatId + "!");
                        Thread.sleep(1000); // Simula o tempo de processamento.
                    } else {
                        System.out.println("❌ " + messagePrefix + " viu que o assento " + seatId + " já estava reservado (" + status + ").");
                    }
                } finally {
                    // --- FIM DA SEÇÃO CRÍTICA ---
                    System.out.println(messagePrefix + " vai liberar o lock para " + seatId + ".");
                    lock.release(); // É crucial liberar o lock em um bloco finally.
                }
            } else {
                System.out.println("❌ " + messagePrefix + " não conseguiu o lock para " + seatId + " a tempo. Outro usuário deve estar reservando.");
            }
        } catch (Exception e) {
            System.err.println("Ocorreu um erro inesperado para " + userId + ": " + e.getMessage());
        }
    }

    /**
     * Entra na competição para se tornar o gerente (líder) da sessão.
     * @param managerId O ID do processo que quer se tornar líder.
     */
    public void becomeSessionManager(String managerId) throws Exception {
        System.out.println("[" + managerId + "] está tentando se tornar o gerente da sessão...");

        // LeaderLatch é uma receita do Curator para eleição de líder.
        try (LeaderLatch leaderLatch = new LeaderLatch(this.client, this.leaderPath, managerId)) {
            leaderLatch.start();
            leaderLatch.await(); // Bloqueia a thread até que este nó seja o líder.

            // --- CÓDIGO EXECUTADO APENAS PELO LÍDER ---
            System.out.println("👑 [" + managerId + "] foi eleito o LÍDER! Agora sou o gerente da sessão.");
            System.out.println("👑 [" + managerId + "] Tarefas do líder: verificar reservas expiradas, etc.");
            Thread.sleep(10000); // Mantém a liderança por 10 segundos para simulação.
            System.out.println("👑 [" + managerId + "] está encerrando suas tarefas e relinquindo a liderança.");
            // O try-with-resources chama leaderLatch.close() automaticamente, liberando a liderança.
        }
    }

    /**
     * Método principal para executar a simulação.
     */
    public static void main(String[] args) throws Exception {
        CinemaDistribuido cinema = new CinemaDistribuido(ZK_HOSTS, SESSION_ID);
        try {
            cinema.setupSeats(TOTAL_SEATS);
            System.out.println("\n--- STATUS INICIAL DOS ASSENTOS ---");
            System.out.println(cinema.getAllSeatsStatus());

            // --- Simulação de Reservas Concorrentes ---
            System.out.println("\n--- INICIANDO SIMULAÇÃO DE RESERVAS CONCORRENTES ---");
            ExecutorService bookingExecutor = Executors.newFixedThreadPool(SEATS_TO_BOOK_IN_PARALLEL.size());
            for (int i = 0; i < SEATS_TO_BOOK_IN_PARALLEL.size(); i++) {
                String seat = SEATS_TO_BOOK_IN_PARALLEL.get(i);
                String user = "Usuario_" + (i + 1);
                bookingExecutor.submit(() -> cinema.bookSeat(seat, user));
            }
            bookingExecutor.shutdown();
            bookingExecutor.awaitTermination(30, TimeUnit.SECONDS);

            System.out.println("\n--- STATUS FINAL DOS ASSENTOS APÓS RESERVAS ---");
            System.out.println(cinema.getAllSeatsStatus());

            // --- Simulação de Eleição de Líder ---
            System.out.println("\n--- INICIANDO SIMULAÇÃO DE ELEIÇÃO DE LÍDER ---");
            ExecutorService managerExecutor = Executors.newFixedThreadPool(3);
            for (int i = 0; i < 3; i++) {
                String managerId = "Gerente_" + (i + 1);
                managerExecutor.submit(() -> {
                    try {
                        cinema.becomeSessionManager(managerId);
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                });
            }
            managerExecutor.shutdown();
            managerExecutor.awaitTermination(45, TimeUnit.SECONDS);

        } finally {
            System.out.println("\nEncerrando o programa.");
            cinema.close();
        }
    }
}