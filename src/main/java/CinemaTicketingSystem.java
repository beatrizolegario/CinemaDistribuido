import org.apache.zookeeper.*;
import org.apache.zookeeper.data.Stat;

import java.nio.ByteBuffer;
import java.util.*;

public class CinemaTicketingSystem {

    private static final int SEAT_PRICE = 14;  // R$ 14 por cadeira
    private static Set<Integer> availableSeats = new HashSet<>();
    private static Set<Integer> selectedSeats = new HashSet<>();
    private static int ticketsSold = 0;
    private static final int MAX_TICKETS = 20;
    private static ZooKeeper zk;
    private static String cinemaQueue = "/cinemaQueue";
    private static String leaderElectionNode = "/leader";
    private static String lockNode = "/lock";
    private static String barrierNode = "/barrier";

    public static void main(String[] args) throws Exception {
        Scanner scanner = new Scanner(System.in);

        // Configuração do Zookeeper
        zk = new ZooKeeper("localhost:2181", 3000, event -> {
            if (event.getState() == Watcher.Event.KeeperState.SyncConnected) {
                synchronized (ZooKeeper.class) {
                    ZooKeeper.class.notify();
                }
            }
        });

        // Garantir que o nó /cinemaQueue exista
        Stat stat = zk.exists("/cinemaQueue", false);
        if (stat == null) {
            zk.create("/cinemaQueue", new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
            System.out.println("Nó /cinemaQueue criado.");
        }

        // Garantir que o nó /barrier exista
        Stat barrierStat = zk.exists("/barrier", false);
        if (barrierStat == null) {
            zk.create("/barrier", new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
            System.out.println("Nó /barrier criado.");
        }

        // Garantir que o nó /barrier/main exista
        Stat barrierMainStat = zk.exists("/barrier/main", false);
        if (barrierMainStat == null) {
            zk.create("/barrier/main", new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
            System.out.println("Nó /barrier/main criado.");
        }

        // Inicializando cadeiras (de 1 a 20 por exemplo)
        for (int i = 1; i <= MAX_TICKETS; i++) {
            availableSeats.add(i);
        }

        // **Liderança** - Eleger um líder
        Leader leader = new Leader(zk, "/election", leaderElectionNode, new Random().nextInt(1000000));
        boolean isLeader = leader.elect();
        if (isLeader) {
            System.out.println("Eu sou o líder, iniciando a venda de ingressos...");
        } else {
            System.out.println("Aguardando o líder para processar...");
        }

        // **Fila** - Gerenciar fila de clientes
        Queue queue = new Queue(zk, cinemaQueue);
        Queue.Consumer consumer = new Queue.Consumer(zk, cinemaQueue);
        consumer.start();  // Consumir tickets na fila

        // **Barreira** - Garantir que todos entrem antes de continuar
        Barrier barrier = new Barrier(zk, barrierNode, 2); // Aqui, 2 participantes
        barrier.enter();  // Todos precisam entrar na barreira

        // **Lock** - Garantir que apenas um processo de compra de ingressos por vez
        Lock lock = new Lock(zk, lockNode, 10000);
        if (lock.lock()) {
            processTicketSale(scanner);  // Chama o método de venda de ingressos
            lock.compute();  // Libera o lock após processar
        } else {
            System.out.println("Não foi possível adquirir o lock. Tentando novamente...");
        }

        barrier.leave();  // Liberar a barreira após o processo

        // Fechar a conexão do Zookeeper
        zk.close();
    }

    // Método para processar a venda de ingressos
    public static void processTicketSale(Scanner scanner) {
        while (true) {
            System.out.println("Cadeiras disponíveis: " + availableSeats);
            System.out.print("Digite o número da cadeira para selecionar (0 para confirmar e sair): ");
            int selectedSeat = scanner.nextInt();

            if (selectedSeat == 0) {
                break;  // Se 0, sai do loop de seleção de cadeiras
            } else if (availableSeats.contains(selectedSeat)) {
                availableSeats.remove(selectedSeat);
                selectedSeats.add(selectedSeat);
                System.out.println("Cadeira " + selectedSeat + " selecionada.");
            } else {
                System.out.println("Cadeira " + selectedSeat + " não disponível ou já selecionada.");
            }
        }

        // Confirmar preço
        int totalAmount = selectedSeats.size() * SEAT_PRICE;
        System.out.println("Total de cadeiras selecionadas: " + selectedSeats.size());
        System.out.println("Total a pagar: R$ " + totalAmount);
        System.out.println("Obrigado pela compra!");
    }
}

// Classe Queue para gerenciar a fila
class Queue {
    private ZooKeeper zk;
    private String root;

    public Queue(ZooKeeper zk, String root) {
        this.zk = zk;
        this.root = root;
    }

    // Tornando a classe Consumer estática
    public static class Consumer extends Thread {
        private ZooKeeper zk;
        private String root;

        public Consumer(ZooKeeper zk, String root) {
            this.zk = zk;
            this.root = root;
        }

        public void run() {
            try {
                // Simula o consumo da fila de tickets
                List<String> list = zk.getChildren(root, true);
                for (String item : list) {
                    // Processa cada ticket da fila
                    ByteBuffer buffer = ByteBuffer.wrap(zk.getData(root + "/" + item, false, null));
                    int ticketId = buffer.getInt();
                    System.out.println("Processando ticket ID: " + ticketId);
                    zk.delete(root + "/" + item, 0);
                }
            } catch (KeeperException | InterruptedException e) {
                e.printStackTrace();
            }
        }
    }

    public boolean produce(int ticketId) throws KeeperException, InterruptedException {
        // Cria um nó na fila para representar a compra de ingresso
        ByteBuffer b = ByteBuffer.allocate(4);
        b.putInt(ticketId);
        byte[] value = b.array();
        zk.create(root + "/ticket", value, ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT_SEQUENTIAL);
        return true;
    }
}

// Classe Lock para garantir o controle exclusivo
class Lock {
    private ZooKeeper zk;
    private String root;
    private long waitTime;

    public Lock(ZooKeeper zk, String root, long waitTime) {
        this.zk = zk;
        this.root = root;
        this.waitTime = waitTime;
    }

    public boolean lock() throws KeeperException, InterruptedException {
        // Garantir que o nó /lock exista antes de tentar criar o nó lock-
        Stat stat = zk.exists(root, false);
        if (stat == null) {
            // Se o nó /lock não existir, cria o nó /lock
            zk.create(root, new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
            System.out.println("Nó /lock criado.");
        }

        // Criação do nó de lock
        String path = zk.create(root + "/lock-", new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.EPHEMERAL_SEQUENTIAL);
        System.out.println("Lock node created: " + path);

        // Verifica se o nó criado é o primeiro da fila
        List<String> children = zk.getChildren(root, false);
        Collections.sort(children);  // Ordena os nós pela sequência

        if (path.equals(root + "/" + children.get(0))) {
            return true;
        } else {
            // Aguarda até que o nó anterior seja deletado
            int index = children.indexOf(path.substring(path.lastIndexOf("/") + 1));
            String prevNode = children.get(index - 1);
            String prevNodePath = root + "/" + prevNode;
            zk.exists(prevNodePath, new Watcher() {
                @Override
                public void process(WatchedEvent event) {
                    if (event.getType() == Watcher.Event.EventType.NodeDeleted) {
                        synchronized (Lock.this) {
                            Lock.this.notify();
                        }
                    }
                }
            });

            // Aguarda até que o nó anterior seja deletado
            synchronized (this) {
                wait();
            }
            return true;
        }
    }

    public void compute() {
        try {
            Thread.sleep(waitTime);  // Simula a execução
            System.out.println("Lock released!");
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }
}

// Classe Barrier para sincronizar processos
class Barrier {
    private ZooKeeper zk;
    private String root;
    private int size;

    public Barrier(ZooKeeper zk, String root, int size) {
        this.zk = zk;
        this.root = root;
        this.size = size;
    }

    public boolean enter() throws KeeperException, InterruptedException {
        // Adiciona o processo à barreira
        String barrierPath = root + "/" + Thread.currentThread().getName();
        zk.create(barrierPath, new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.EPHEMERAL_SEQUENTIAL);

        // Verifica se todos os participantes estão prontos
        List<String> list = zk.getChildren(root, true);
        if (list.size() < size) {
            synchronized (this) {
                this.wait();
            }
        } else {
            return true;
        }
        return false;
    }

    public void leave() throws KeeperException, InterruptedException {
        zk.delete(root + "/" + Thread.currentThread().getName(), 0);
    }
}

// Classe Leader para coordenar a eleição
class Leader {
    private ZooKeeper zk;
    private String root;
    private String leaderNode;
    private String id;

    public Leader(ZooKeeper zk, String root, String leaderNode, int id) {
        this.zk = zk;
        this.root = root;
        this.leaderNode = leaderNode;
        this.id = String.valueOf(id);
    }

    public boolean elect() throws KeeperException, InterruptedException {
        String leaderPath = root + "/leader";

        // Verificar se o nó /election existe
        Stat stat = zk.exists(root, false);
        if (stat == null) {
            // O nó /election não existe, então criamos ele
            zk.create(root, new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
            System.out.println("Nó /election criado.");
        }

        // Verificar se o nó /election/leader já existe
        stat = zk.exists(leaderPath, false);
        if (stat == null) {
            // Se o nó /election/leader não existe, crie-o
            zk.create(leaderPath, id.getBytes(), ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.EPHEMERAL);
            System.out.println("Eu sou o líder: " + id);
            return true;
        } else {
            // Se o nó /election/leader já existe, outro líder foi eleito
            System.out.println("Líder já existe: aguardando...");
            return false;
        }
    }
}

