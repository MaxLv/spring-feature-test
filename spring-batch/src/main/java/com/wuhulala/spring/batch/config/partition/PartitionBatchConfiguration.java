package com.wuhulala.spring.batch.config.partition;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.wuhulala.spring.batch.config.partition.kafka.KafkaPartitionHandler;
import com.wuhulala.spring.batch.config.simple.SimpleItemProcessor;
import com.wuhulala.spring.batch.config.simple.SimpleItemWriter;
import com.wuhulala.spring.batch.listener.batch.MyJobListener;
import com.wuhulala.spring.batch.model.Person;
import org.apache.commons.dbcp.BasicDataSource;
import org.springframework.batch.core.*;
import org.springframework.batch.core.configuration.annotation.EnableBatchProcessing;
import org.springframework.batch.core.configuration.annotation.JobBuilderFactory;
import org.springframework.batch.core.configuration.annotation.StepBuilderFactory;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.batch.core.launch.support.RunIdIncrementer;
import org.springframework.batch.core.launch.support.SimpleJobLauncher;
import org.springframework.batch.core.partition.PartitionHandler;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.repository.support.JobRepositoryFactoryBean;
import org.springframework.batch.integration.partition.BeanFactoryStepLocator;
import org.springframework.batch.integration.partition.StepExecutionRequest;
import org.springframework.batch.integration.partition.StepExecutionRequestHandler;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.batch.item.ItemReader;
import org.springframework.batch.item.ItemWriter;
import org.springframework.batch.item.file.FlatFileItemReader;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.task.TaskExecutor;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.text.SimpleDateFormat;

/**
 * 0_0 o^o
 *
 * @author wuhulala<br>
 * @date 2018/10/15<br>
 * @description o_o<br>
 * @since v1.0<br>
 */
@Configuration
@EnableBatchProcessing
public class PartitionBatchConfiguration {


    public static final int GRID_SIZE = 2;
    @Autowired
    JobBuilderFactory jobBuilderFactory;

    @Autowired
    StepBuilderFactory stepBuilderFactory;

    @Bean
    public DataSource dataSource(){
        BasicDataSource dataSource = new BasicDataSource();
        dataSource.setDriverClassName("oracle.jdbc.OracleDriver");
        dataSource.setUsername("hs_sys");
        dataSource.setPassword("Hundsun123");
        dataSource.setUrl("jdbc:oracle:thin:@127.0.0.1:1521:xe");
        dataSource.setMaxActive(10);
        dataSource.setMaxIdle(10);
        dataSource.setMaxWait(10000);
        dataSource.setTestWhileIdle(true);
        dataSource.setValidationQuery("select 1 from dual");
        return dataSource;
    }

    @Bean
    public JobRepository jobRepository(DataSource dataSource, PlatformTransactionManager transactionManager) throws Exception {
        JobRepositoryFactoryBean jobRepositoryFactoryBean = new JobRepositoryFactoryBean();
        jobRepositoryFactoryBean.setDataSource(dataSource);
        jobRepositoryFactoryBean.setTransactionManager(transactionManager);
        jobRepositoryFactoryBean.setDatabaseType("oracle");
        return jobRepositoryFactoryBean.getObject();
    }

    @Bean
    public SimpleJobLauncher jobLauncher(DataSource dataSource, PlatformTransactionManager transactionManager) throws Exception {
        SimpleJobLauncher jobLauncher = new SimpleJobLauncher();
        jobLauncher.setJobRepository(jobRepository(dataSource, transactionManager));
        return jobLauncher;
    }


    @Bean
    public JobExecutionListener myJobListener() {
        return new MyJobListener();
    }

    @Bean
    public Job partitionJob(JobBuilderFactory jobs,
                            @Qualifier("masterStep") Step masterStep,
                            @Qualifier("slaveStep") Step slaveStep) {
        return jobs.get("partitionJob")
                .start(masterStep)
                .incrementer(new RunIdIncrementer())
                .listener(new MyJobListener())
                .build();
    }

    @Bean(name = "masterStep")
    public Step masterStep(@Qualifier("slaveStep") Step slaveStep,
                           PartitionHandler partitionHandler,
                           DataSource dataSource) {
        return stepBuilderFactory.get("masterStep")
                // 配置一个PartitionStep
                .partitioner(slaveStep.getName(), new FilePartitioner())
                .step(slaveStep)
                .partitionHandler(partitionHandler)
                .build();
    }

    @Bean(name = "slaveStep")
    public Step slaveStep(ItemReader<Person> reader, ItemWriter<Person> writer, ItemProcessor<Person, Person> processor) {
        return stepBuilderFactory.get("slaveStep")
                .<Person, Person>chunk(5)
                .reader(reader)
                .processor(processor)
                .writer(writer)
                .build();
    }


    @Bean
    @StepScope // 必须和实现类同时使用，即返回值必须是实现类
    public FlatFileItemReader<Person> reader(@Value("#{stepExecutionContext['input.file.path']}")String filePath) {
        final SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMddHHmmss");
        //ItemReader<Person>
        FlatFileItemReader<Person> reader = new FlatFileItemReader<>();
        reader.setResource(new ClassPathResource(filePath));
        reader.setLineMapper((s, i) -> {
            String[] ss = s.split(",");
            Person person = new Person();
            person.setName(ss[0]);
            person.setAge(ss[1]);
            person.setCreateTime(sdf.parse(ss[2]));
            return person;
        });
        return reader;
    }


    @Bean
    public ItemWriter<Person> writer() {
        //ItemReader<Person>

        return new SimpleItemWriter();
    }


    @Bean
    public ItemProcessor<Person, Person> processor() {
        //ItemReader<Person>
        ItemProcessor<Person, Person> processor = new SimpleItemProcessor();

        return processor;
    }

    //=============================分区===========================================
    ///////////////////////////// 方法区 ////////////////////////////////////

    //    @Bean
//    public PartitionHandler partitionHandler(TaskExecutor taskExecutor, @Qualifier("slaveStep") Step slaveStep ){
//        TaskExecutorPartitionHandler partitionHandler = new TaskExecutorPartitionHandler();
//        partitionHandler.setGridSize(2);
//        partitionHandler.setStep(slaveStep);
//        partitionHandler.setTaskExecutor(taskExecutor);
//        return partitionHandler;
//    }
    @Bean
    @Autowired
    public PartitionHandler partitionHandler(KafkaTemplate kafkaTemplate) throws Exception {
        KafkaPartitionHandler partitionHandler = new KafkaPartitionHandler();
        partitionHandler.setStepName("slaveStep");
        partitionHandler.setGridSize(GRID_SIZE);
        partitionHandler.setKafkaTemplate(kafkaTemplate);
        partitionHandler.setJobExplorer(jobExplorer);
        partitionHandler.setTopic("hello-batch");
        //partitionHandler.setPollRepositoryForResults(true);
        return partitionHandler;
    }

    @Bean
    public TaskExecutor taskExecutor(){
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(10);
        executor.setThreadNamePrefix("partitionHandler");
        return executor;
    }

    @Autowired
    private ApplicationContext applicationContext;
    @Autowired
    public JobExplorer jobExplorer;

    @Bean
    public StepExecutionRequestHandler stepExecutionRequestHandler() {
        StepExecutionRequestHandler stepExecutionRequestHandler = new StepExecutionRequestHandler();
        BeanFactoryStepLocator stepLocator = new BeanFactoryStepLocator();
        stepLocator.setBeanFactory(this.applicationContext);
        stepExecutionRequestHandler.setStepLocator(stepLocator);
        stepExecutionRequestHandler.setJobExplorer(this.jobExplorer);
        return stepExecutionRequestHandler;
    }

    @Autowired
    private StepExecutionRequestHandler stepExecutionRequestHandler;

    @KafkaListener(topics = {"hello-batch"})
    public String doHandle(String content){
        System.out.println("slave接收到消息：:" + content);
        JSONObject jsonObject = JSON.parseObject(content);

        StepExecutionRequest request = new StepExecutionRequest(jsonObject.getString("stepName"),
                jsonObject.getLong("jobExecutionId"),
                jsonObject.getLong("stepExecutionId"));
        StepExecution stepExecution = stepExecutionRequestHandler.handle(request);

        return stepExecution.getStatus().equals(BatchStatus.COMPLETED) ? "success" : stepExecution.getStatus().toString();
    }



}
