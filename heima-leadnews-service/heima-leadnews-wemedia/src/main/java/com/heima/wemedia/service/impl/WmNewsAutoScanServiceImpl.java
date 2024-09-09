package com.heima.wemedia.service.impl;

import com.alibaba.fastjson.JSONArray;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.heima.apis.article.IArticleClient;
import com.heima.common.aliyun.GreenImageScan;
import com.heima.common.aliyun.GreenTextScan;
import com.heima.common.tess4j.Tess4jClient;
import com.heima.file.service.FileStorageService;
import com.heima.model.article.dtos.ArticleDto;
import com.heima.model.common.dtos.ResponseResult;
import com.heima.model.wemedia.pojos.WmChannel;
import com.heima.model.wemedia.pojos.WmNews;
import com.heima.model.wemedia.pojos.WmSensitive;
import com.heima.model.wemedia.pojos.WmUser;
import com.heima.utils.common.SensitiveWordUtil;
import com.heima.wemedia.mapper.WmChannelMapper;
import com.heima.wemedia.mapper.WmNewsMapper;
import com.heima.wemedia.mapper.WmSensitiveMapper;
import com.heima.wemedia.mapper.WmUserMapper;
import com.heima.wemedia.service.WmNewsAutoScanService;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
public class WmNewsAutoScanServiceImpl implements WmNewsAutoScanService {
    @Autowired
    private WmNewsMapper wmNewsMapper;
    @Autowired
    private FileStorageService fileStorageService;

    @Autowired
    private GreenImageScan greenImageScan;
    @Autowired
    private GreenTextScan greenTextScan;
    @Autowired
    private WmChannelMapper wmChannelMapper;
    @Autowired
    private WmUserMapper wmUserMapper;
    @Autowired
    private IArticleClient articleClient;
    @Autowired
    private WmSensitiveMapper wmSensitiveMapper;
    @Autowired
    private Tess4jClient tess4jClient;
    @Autowired
    ApplicationContext applicationContext;


    WmNewsAutoScanServiceImpl proxy;
    public WmNewsAutoScanServiceImpl getProxy() {
        if (proxy == null) {
            // 延迟获取代理对象
            proxy = applicationContext.getBean(WmNewsAutoScanServiceImpl.class);
        }
        return proxy;
    }
    @Override
    @Async
    public void autoScanWmNews(Integer id) {
        //1.查询自媒体文章
        WmNews wmNews = wmNewsMapper.selectById(id);
        if (wmNews == null) {
            throw new RuntimeException("WmNewsAutoScanServiceImpl-文章不存在");
        }
        if(wmNews.getStatus().equals(WmNews.Status.SUBMIT.getCode())){
          Map<String,Object> textAndImages=  handleTextAndImages(wmNews);
            //自管理的敏感词过滤
            boolean isSensitive = handleSensitiveScan((String) textAndImages.get("content"), wmNews);
            if(!isSensitive) return;
            //2.审核文本内容  阿里云接口
            boolean isTextScan =handleTextScan((String)textAndImages.get("content"),wmNews);
            if(!isTextScan){
                return;
            }
            //3.审核图片  阿里云接口
            boolean isImageScan =handleImageScan((List<String>)textAndImages.get("images"),wmNews);
            if(!isImageScan){
                return;
            }

            //4.审核成功，保存app端的相关的文章数据
            ResponseResult responseResult = saveAppArticle(wmNews);
            if(responseResult.getCode()!=200){
                throw new RuntimeException("WmNewsAutoScanServiceImpl-文章审核，保存app端相关文章数据失败");
            }
            wmNews.setArticleId((Long) responseResult.getData());
            getProxy().updateWmNews(wmNews,WmNews.Status.PUBLISHED.getCode(), "审核成功");
        }
    
    }

    public boolean handleSensitiveScan(String content, WmNews wmNews) {
        boolean flag = true;
        List<WmSensitive> wmSensitives = wmSensitiveMapper.selectList(Wrappers.<WmSensitive>lambdaQuery().select(WmSensitive::getSensitives));
        List<String> sensitiveList = wmSensitives.stream().map(WmSensitive::getSensitives).collect(Collectors.toList());
        SensitiveWordUtil.initMap(sensitiveList);
        Map<String, Integer> map = SensitiveWordUtil.matchWords(content);
        if (map.size()>0){
            getProxy().updateWmNews(wmNews,WmNews.Status.FAIL.getCode(),"当前文章中存在违规内容"+map);
            flag = false;
        }
        return flag;


    }

    private ResponseResult saveAppArticle(WmNews wmNews) {
        ArticleDto articleDto = new ArticleDto();
        BeanUtils.copyProperties(wmNews,articleDto);
        articleDto.setLayout(wmNews.getType());
        WmChannel wmChannel = wmChannelMapper.selectById(wmNews.getChannelId());
        if(wmChannel!=null){
            articleDto.setChannelName(wmChannel.getName());
        }
        articleDto.setAuthorId(Long.valueOf(wmNews.getUserId()));
        WmUser wmUser = wmUserMapper.selectById(wmNews.getUserId());
        if(wmUser!=null){
            articleDto.setAuthorName(wmUser.getName());
        }
        if(wmNews.getArticleId()!=null){
            articleDto.setId(wmNews.getArticleId());
        }
        articleDto.setCreatedTime(new Date());
        ResponseResult result = articleClient.saveArticle(articleDto);
        return result;
    }

    private boolean handleImageScan(List<String> images, WmNews wmNews) {
        boolean flag = true;
        if(images == null || images.size() == 0){
            return flag;
        }
        images = images.stream().distinct().collect(Collectors.toList());
        List<byte[]> imageList = new ArrayList<>();
        try {
            for (String image : images) {
                byte[] bytes = fileStorageService.downLoadFile(image);

                //图片识别文字审核---begin-----

                //从byte[]转换为butteredImage
                ByteArrayInputStream in = new ByteArrayInputStream(bytes);
                BufferedImage imageFile = ImageIO.read(in);
                //识别图片的文字
                String result = tess4jClient.doOCR(imageFile);

                //审核是否包含自管理的敏感词
                boolean isSensitive = handleSensitiveScan(result, wmNews);
                if(!isSensitive){
                    return isSensitive;
                }

                //图片识别文字审核---end-----
                imageList.add(bytes);

            }
        }catch (Exception e){
            e.printStackTrace();
        }
        try {
            Map map = greenImageScan.imageScan(imageList);
            if (map != null) {
                if(map.get("suggestion").equals("block")){
                    getProxy().updateWmNews(wmNews,WmNews.Status.FAIL.getCode(),"当前文章中存在违规内容");
                    flag = false;
                }
                if(map.get("suggestion").equals("review")){
                    getProxy().updateWmNews(wmNews,WmNews.Status.ADMIN_AUTH.getCode(),"当前文章中存在不确定内容");
                    flag = false;
                }
            }

        } catch (Exception e) {
            flag = false;
            e.printStackTrace();
        }
        return flag;
    }

    private boolean handleTextScan(String content, WmNews wmNews) {
        boolean flag = true;
        if((wmNews.getTitle()+content).length() == 0){
            return flag;
        }
        try {
            Map map = greenTextScan.greeTextScan(wmNews.getTitle() + "-" + content);
            if (map != null) {
                if(map.get("suggestion").equals("block")){
                    getProxy().updateWmNews(wmNews,WmNews.Status.FAIL.getCode(),"当前文章中存在违规内容");
                    flag = false;
                }
                if(map.get("suggestion").equals("review")){
                    getProxy().updateWmNews(wmNews,WmNews.Status.ADMIN_AUTH.getCode(),"当前文章中存在不确定内容");
                    flag = false;
                }
            }
        } catch (Exception e) {
            flag = false;
            e.printStackTrace();
        }
        return flag;
    }
    @Transactional
    public void updateWmNews(WmNews wmNews, Short status, String reason) {
        wmNews.setStatus(status);
        wmNews.setReason(reason);
        wmNewsMapper.updateById(wmNews);
    }


    private Map<String, Object> handleTextAndImages(WmNews wmNews) {
        //存储纯文本内容
        StringBuilder stringBuilder = new StringBuilder();
        ArrayList<String> images = new ArrayList<>();
        //1。从自媒体文章的内容中提取文本和图片
            if(StringUtils.isNotBlank(wmNews.getContent())){
                List<Map> maps = JSONArray.parseArray(wmNews.getContent(), Map.class);
                for (Map map : maps) {
                    if(map.get("type").equals("text")){
                        stringBuilder.append(map.get("value"));
                    }
                    if(map.get("type").equals("image")){
                        images.add((String) map.get("value"));
                    }
                }
            }
        //2.提取文章的封面图片
        if(StringUtils.isNotBlank(wmNews.getImages())){
            String[] split = wmNews.getImages().split(",");
            images.addAll(Arrays.asList(split));
        }
        HashMap<String, Object> resultMap = new HashMap<>();
        resultMap.put("images", images);
        resultMap.put("content", stringBuilder.toString());
        return resultMap;
    }
}
