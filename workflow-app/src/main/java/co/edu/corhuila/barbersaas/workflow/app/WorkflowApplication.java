package co.edu.corhuila.barbersaas.workflow.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "co.edu.corhuila.barbersaas.workflow")
public class WorkflowApplication {
    public static void main(String[] args) {
        SpringApplication.run(WorkflowApplication.class, args);
    }
}
