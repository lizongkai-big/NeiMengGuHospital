package com.system.controller;


import com.system.controller.util.ExceptionHandlerController;
import com.system.entity.DB2.test1.PtsVwCyxx;
import com.system.entity.DB2.test1.PtsVwRyxx;
import com.system.entity.DB2.test1.PtsVwZyxx;
import com.system.entity.SqlServer.PtsVwSsxx;
import com.system.entity.SyncLog;
import com.system.entity.SysHospitalization;
import com.system.facade.RYXXToUserService;
import com.system.facade.ZYXXAndSSXXToSurgeryService;
import com.system.service.SynLogService;
import com.system.service.SysHospitalizationService;
import com.system.service.SysUserService;
import com.system.util.CheckException;
import com.system.util.exception.controller.result.NoneRemoveException;
import com.system.util.exception.controller.result.NoneSaveException;
import com.system.util.exception.controller.result.NoneUpdateException;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import static com.system.util.tools.DateFormatHelper.getTodayDate;

//import com.system.facade.RYXXToUserService;

/**
 * @Auther: 李景然
 * @Date: 2018/7/2 11:13
 * @Description:
 */
@RestController
@Api(tags = "synchronousDBController", description = "同步数据库相关操作")
@RequestMapping(value = "/synchronousDBController")
@CheckException(reason = "同步数据库操作参数的合法性")
public class SynchronousDBController {

    private Logger logger = LoggerFactory.getLogger(ExceptionHandlerController.class);
    Timer syncHosTimer;
    Timer syncUserTimer;

    @Resource
    private SysHospitalizationService sysHospitalizationService;

    @Resource
    private SynLogService synLogService;
//
//    @ApiOperation(value = "同步病人在院信息(分钟)")
//    @RequestMapping(value = "/syncHos/{period}", method = RequestMethod.GET)
//    @ResponseStatus(HttpStatus.OK)
//    public boolean synchronousHos(@PathVariable Long period) {
//
//       if (syncHosTimer != null) {
//           syncHosTimer.cancel();
//       }
//        syncHosTimer = new Timer();
//        //周期任务执行的开始时间
//        Date beginTime = new Date();
//        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
//
//
//        logger.info("period(分钟)---" + period);
//        period = 1000 * 60 * period;
//
//        syncHosTimer.schedule(new TimerTask() {
//            int num = 0;
//
//            @Override
//            public void run() {
//                System.out.println("第几次---" + ++num);
//                //取db2数据的开始、截止时间
//                Date startTime = null;
//                Date endTime = new Date();
//
//                SyncLog lastSyncLog = synLogService.getLastSynLog("hspt");
//                if (lastSyncLog == null) {
//                    startTime=new Date(0);
//                } else {
//                    startTime = lastSyncLog.getsEndtime();
//                }
//
//                // 执行你的方法
//                List<PtsVwZyxx> list = zyxxAndCYXXToHosService.getZYXXList(startTime, endTime);
//                if (list != null && list.size() > 0) {
//                    System.out.println("获取 病人信息的总条数count---" + list.size());
//                    for (PtsVwZyxx item : list) {
//                        boolean bl = zyxxAndCYXXToHosService.insertHos(item);
//                        if (bl == false) {
//                            throw new NoneRemoveException();
//                        }
//                    }
//                    SyncLog syncLog = new SyncLog();
//                    syncLog.setsCount((long) list.size());
//                    syncLog.setsEndtime(endTime);
//                    syncLog.setsStarttime(startTime);
//                    syncLog.setsSuccess(true);
//                    syncLog.setsType("hspt");
//                    synLogService.insertSynLog(syncLog);
//
//
//                    System.out.println("startTime---" + startTime);
//                    System.out.println("endTime---" + endTime);
//                    System.out.println("插入 病人信息count---" + list.size());
//                } else {
//                    System.out.println("startTime---" + startTime);
//                    System.out.println("endTime---" + endTime);
//                    System.out.println("暂无需要同步 插入 病人信息的数据");
//                }
//
//                //修改 出院状态
//                List<SysHospitalization> zaiyuanList= sysHospitalizationService.getList(1);
//                if(zaiyuanList!=null&&zaiyuanList.size()>0){
//                    for (SysHospitalization item :zaiyuanList){
//                        PtsVwCyxx ptsVwCyxx=zyxxAndCYXXToHosService.getCYXX(item.gethId(),item.gethTimes());
//                        if(ptsVwCyxx!=null){
//                            item.setpStatus(3);
//                            sysHospitalizationService.update(item);
//                        }
//                    }
//                }
//            }
//        }, beginTime, period);
//
//        return true;
//
//
//    }

    // 手术信息同步分析：
    // 因为pts_vw_ssxx视图里的手术日期可能为空（还没有排上手术），可能不为空；
    // 一种同步方式是完全跟踪视图的变化，没有的数据进行新增，有了的数据可能需要更新；
    // 还有一种同步方式是因为系统目前显示的只有当天的数据，那么我只同步手术日期是当天的数据就OK咯；
    // 第二种同步操作，只插入不需要更新，因为每天的手术数据也就100-200条，所以同步起来毫不费力
    // 缺点就是数据库中只存在今天以及以前的数据，万一前台想看以后的手术信息就看不到
    // 所以中和一下就是同步所有手术日期大于上次同步日期的信息，这个数据量并不大；这种方法跟住院和用户的同步方式相似
    // 但是万一有手术日期新增并且在上次同步之前呢？
    // 同时因为后两种同步方式没有更新操作，第一种同步插入一个没有手术日期的信息之后，别的就插入不进去！
    Timer syncSgTimer;
    @Resource
    private ZYXXAndSSXXToSurgeryService zyxxAndSSXXToSurgeryService;

    @ApiOperation(value = "同步病人手术信息，period时间间隔，delay延迟，syncType同步类型（1.当天 2.所有带日期（还未实现） 3.所有）")
    @RequestMapping(value = {"/syncSurgery/{period}", "/syncSurgery/{period}/{delay}", "/syncSurgery/{period}/{delay}/{syncType}"}, method = RequestMethod.GET)
    @ResponseStatus(HttpStatus.OK)
    public boolean syncSurgery(@PathVariable Long period, @PathVariable(required = false) Long delay, @PathVariable(required = false) Integer syncType) {
        if (delay == null) {
            logger.info("delay == null");
            delay = 0l;
        }
        if (syncType == null) {
            logger.info("syncType == null");
            syncType = 3;
        }
        String syncTypeStr = syncType == 1 ? "syncToday" : syncType == 2 ? "syncDate" : syncType == 3 ? "syncAll" : "error";
        if (syncSgTimer != null) {
            syncSgTimer.cancel();
        }
        syncSgTimer = new Timer();

        logger.info("SSXX_ALL period(分钟)---" + period);
        logger.info("SSXX_ALL delay(分钟)---" + delay);
        period = 1000 * 60 * period;
        delay =  1000 * 60 * delay;
        final int syncTypeFinal = syncType;

        syncSgTimer.schedule(new TimerTask() {
            int num = 0;
            @Override
            public void run() {
                logger.info("SSXX---times---" + ++num);
                Date startTime = new Date();

                final LongAdder update = new LongAdder();
                final LongAdder insert = new LongAdder();
                final LongAdder none = new LongAdder();
                List<PtsVwSsxx> list = null;
                // 根据同步类型确定需要同步的数据
                if (syncTypeFinal == 1) { // 当天的手术信息
                    Calendar cal = Calendar.getInstance();
                    Date todayDate = getTodayDate();
                    cal.setTime(todayDate);
                    cal.add(Calendar.DATE, 1);
                    list = zyxxAndSSXXToSurgeryService.getSSXXListBySgDate(todayDate, cal.getTime());
                }
                else if (syncTypeFinal == 2) { // 从上次同步的手术日期开始同步
                    throw new NoneUpdateException("同步方法未实现");
                }
                else if (syncTypeFinal == 3) { // 同步所有数据
                    list = zyxxAndSSXXToSurgeryService.getAllSSXXList();
                }
                else {
                    throw new NoneUpdateException("同步方法参数不对");
                }
                long totalNum = Long.valueOf(list.size());
                logger.info("手术信息总数：" + totalNum);
                // 多线程同步 分3个线程，并最先处理总量的后面的1/9
                // 线程总数
                int threadCount = 0;
                // 根据手术信息的总数确定线程数目
                if (totalNum < 500) {
                    threadCount = 1;
                }
                else if (totalNum < 1000) {
                    threadCount = 2;
                }
                else {
                    threadCount = 3;
                }
                // 数据的份数，设为线程总数的平方
                int pieceNum = threadCount * threadCount;
                // 每份数据的大小
                int pieceSize = (int)(totalNum / pieceNum);
                ExecutorService fixedThreadPool = Executors.newFixedThreadPool(threadCount);
                // 每一份数据都是后面的优先进行同步
                for (int i = pieceNum; i >= 1; i--) {
                    List<PtsVwSsxx> subList = list.subList(
                            (i-1) * pieceSize,
                            (int)(i * pieceSize > totalNum ? totalNum : i * pieceSize));
                    fixedThreadPool.execute(new Runnable() {
                        @Override
                        public void run() {
                            // 从pts_vw_ssxx视图中获取到所有手术信息，并获取到住院信息和出院信息（如果住院信息未获取到），
                            // 然后往sys_surgery中插入或更新数据；
                            // 同时记录本次同步的总数（也就是当前手术信息总数），更新的数目，新增的数目，未变化的数目，开始时间，结束时间
                            for (PtsVwSsxx item : subList) {
                                PtsVwZyxx zyxx = zyxxAndSSXXToSurgeryService.getZyxx(item.getZYH(), item.getZYCS());
                                PtsVwCyxx cyxx = null;
                                if (zyxx == null) {
                                    cyxx = zyxxAndSSXXToSurgeryService.getCyxx(item.getZYH(), item.getZYCS());
                                }
                                String result = zyxxAndSSXXToSurgeryService.insertOrUpdateSurgery(item, zyxx, cyxx);
                                // 记录本次同步的行为
                                if (result.equals("insert")) insert.increment();
                                else if (result.equals("update")) update.increment();
                                else none.increment();
                            }
                        }
                    });
                }
                fixedThreadPool.shutdown();
                try {//等待直到所有任务完成
                    fixedThreadPool.awaitTermination(Long.MAX_VALUE, TimeUnit.MINUTES);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }

                long insertNum = insert.sum();
                long updateNum = update.sum();
                long noneNum = none.sum();
                // 往数据库中记录本次同步的详细信息
                SyncLog syncLog = new SyncLog();
                syncLog.setsCount(totalNum);
                syncLog.setsStarttime(startTime);
                syncLog.setsEndtime(new Date());
                syncLog.setsSuccess(totalNum == (insertNum + updateNum + noneNum));
                syncLog.setsInsert(insertNum);
                syncLog.setsUpdate(updateNum);
                syncLog.setsType(syncTypeStr);
                synLogService.insertSynLog(syncLog);

                logger.info("SSXX---times--- 第 "+ num +" 次同步，新增数目：" + insertNum + "，更新数目：" + updateNum);
            }
        },delay, period); // delay为0，是立即执行
        // 处理逻辑：
        return true;
    }

    @Resource
    private RYXXToUserService ryxxToUserService;

    @Resource
    private SysUserService sysUserService;

    @ApiOperation(value = "同步用户信息")
    @RequestMapping(value = "/syncUser/{period}", method = RequestMethod.GET)
    @ResponseStatus(HttpStatus.OK)
    public boolean syncUser(@PathVariable Long period) {
        //周期任务执行的开始时间
        Date beginTime = new Date();
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

        if (syncUserTimer != null) {
            syncUserTimer.cancel();
        }
        syncUserTimer = new Timer();
        logger.info("period(分钟)---" + period);
        period = 1000 * 60 * period;

        syncUserTimer.schedule(new TimerTask() {
            int num = 0;

            @Override
            public void run() {
                logger.info("第几次---" + ++num);
                //取db2数据的开始、截止时间
                Date startTime = null;
                Date endTime = new Date();

                SyncLog lastSyncLog = synLogService.getLastSynLog("user");
                if (lastSyncLog == null) {
                    startTime=new Date(0);
                } else {
                    startTime = lastSyncLog.getsEndtime();
                }

                // 执行你的方法
                List<PtsVwRyxx> list = ryxxToUserService.getRYXXList();
                if (list != null && list.size() > 0) {
                    for (PtsVwRyxx item : list) {
                        boolean bl = sysUserService.isHave(item.getCodeno());
                        if (bl == false) {
                            boolean blInsert = ryxxToUserService.insertRYXX(item);
                            if (blInsert == false) {
                                throw new NoneSaveException();
                            }
                        }
                    }
                    SyncLog syncLog = new SyncLog();
                    syncLog.setsCount((long) list.size());
                    syncLog.setsEndtime(endTime);
                    syncLog.setsStarttime(startTime);
                    syncLog.setsSuccess(true);
                    syncLog.setsType("user");
                    synLogService.insertSynLog(syncLog);

                    logger.info("startTime---" + startTime);
                    logger.info("endTime---" + endTime);
                    logger.info("同步 用户 的数据count---" + list.size());
                } else {
                    logger.info("startTime---" + startTime);
                    logger.info("endTime---" + endTime);
                    logger.info("暂无需要同步 用户 的数据");
                }
            }
        }, beginTime, period);
        return true;
    }
}
