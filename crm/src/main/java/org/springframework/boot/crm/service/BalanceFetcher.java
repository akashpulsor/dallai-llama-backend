package org.springframework.boot.crm.service;

import java.io.IOException;
import java.time.LocalDate;
import java.util.Date;

public interface BalanceFetcher {


    String fetchUsageData(LocalDate startDate, LocalDate endDate) throws IOException, InterruptedException ;


    double calculateTotalUsage(LocalDate startDate) throws IOException, InterruptedException;
}
