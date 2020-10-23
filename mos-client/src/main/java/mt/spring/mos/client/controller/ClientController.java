package mt.spring.mos.client.controller;

import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.extern.slf4j.Slf4j;
import mt.spring.mos.client.entity.MosClientProperties;
import mt.spring.mos.client.entity.ResResult;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.util.Assert;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import javax.servlet.http.HttpServletResponse;
import java.io.*;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * @Author Martin
 * @Date 2020/5/15
 */
@RestController
@RequestMapping("/client")
@Api(tags = "文件上传接口")
@Slf4j
public class ClientController implements InitializingBean {
	@Autowired
	private MosClientProperties mosClientProperties;
	
	private String getAvaliableBasePath(long fileSize) {
		String[] basePaths = mosClientProperties.getBasePaths();
		Assert.notNull(basePaths, "未配置basePath");
		List<File> collect = Arrays.stream(basePaths).map(File::new).filter(file1 -> {
			//空闲空间占比
			long freeSpace = file1.getFreeSpace();
			return freeSpace > fileSize && BigDecimal.valueOf(freeSpace).compareTo(mosClientProperties.getMinAvaliableSpaceGB().multiply(BigDecimal.valueOf(1024L * 1024 * 1024))) > 0;
		}).collect(Collectors.toList());
		Assert.notEmpty(collect, "无可用存储空间使用");
		return collect.get(new Random().nextInt(collect.size())).getPath();
	}
	
	@PostMapping("/upload")
	@ApiOperation("上传文件")
	public ResResult upload(MultipartFile file, String pathname, HttpServletResponse response) {
		Assert.notNull(file, "上传的文件不能为空");
		Assert.notNull(pathname, "pathname不能为空");
		Assert.state(!pathname.contains(".."), "路径非法");
		log.info("上传文件：{}", pathname);
		String[] basePaths = mosClientProperties.getBasePaths();
		Assert.notNull(basePaths, "未配置basePath");
		File desFile = new File(getAvaliableBasePath(file.getSize()), pathname);
		if (desFile.exists()) {
			log.info("文件已存在，进行覆盖上传");
		}
		File parentFile = desFile.getParentFile();
		if (!parentFile.exists()) {
			log.info("创建路径：{}", parentFile.getPath());
			parentFile.mkdirs();
		}
		log.info("上传至：{}", desFile.getPath());
		try (InputStream inputStream = file.getInputStream();
			 OutputStream outputStream = new FileOutputStream(desFile)) {
			log.info("进行流拷贝...");
			IOUtils.copyLarge(inputStream, outputStream);
			log.info("{}上传完成!", pathname);
			return new ResResult("上传成功");
		} catch (IOException e) {
			response.setStatus(500);
			log.error(pathname + "上传失败", e);
			ResResult resResult = new ResResult();
			resResult.setStatus(ResResult.ERROR);
			resResult.setMessage("上传失败");
			return resResult;
		}
	}
	
	@DeleteMapping("/deleteFile")
	public ResResult deleteFile(String pathname) {
		Assert.state(StringUtils.isNotBlank(pathname), "pathname不能为空");
		Assert.state(!pathname.contains(".."), "pathname非法");
		String[] basePaths = mosClientProperties.getBasePaths();
		if (basePaths != null) {
			for (String basePath : basePaths) {
				File file = new File(basePath, pathname);
				if (file.exists()) {
					log.info("删除文件：{}", file.getPath());
					FileUtils.deleteQuietly(file);
				}
			}
		}
		return new ResResult();
	}
	
	@DeleteMapping("/deleteDir")
	public ResResult deleteDir(String path) throws IOException {
		Assert.state(StringUtils.isNotBlank(path), "文件夹不能为空");
		Assert.state(!path.contains(".."), "路径非法");
		if (!path.startsWith("/")) {
			path = "/" + path;
		}
		String[] basePaths = mosClientProperties.getBasePaths();
		if (basePaths != null) {
			for (String basePath : basePaths) {
				File file = new File(basePath + path);
				if (file.exists() && file.isDirectory()) {
					log.info("删除文件夹：{}", file.getPath());
					FileUtils.deleteDirectory(file);
					break;
				}
			}
		}
		return new ResResult();
	}
	
	@GetMapping("/size")
	public ResResult size(String pathname) {
		String[] basePaths = mosClientProperties.getBasePaths();
		if (basePaths == null) {
			return new ResResult(-1);
		}
		if (!pathname.startsWith("/")) {
			pathname = "/" + pathname;
		}
		for (String basePath : basePaths) {
			File file = new File(basePath + pathname);
			if (file.exists()) {
				return new ResResult(FileUtils.sizeOf(file));
			}
		}
		return new ResResult(-1);
	}
	
	@PutMapping("/moveFile")
	public ResResult moveFile(String srcPathname, String desPathname) {
		Assert.notNull(srcPathname, "srcPathname不能为空");
		Assert.state(!srcPathname.contains(".."), "非法路径" + srcPathname);
		Assert.notNull(desPathname, "desPathname不能为空");
		Assert.state(!desPathname.contains(".."), "非法路径" + desPathname);
		Stream.of(mosClientProperties.getBasePaths())
				.filter(s -> new File(s, srcPathname).exists())
				.forEach(basePath -> {
					File srcFile = new File(basePath, srcPathname);
					File desFile = new File(basePath, desPathname);
					Assert.state(!desFile.exists(), "目标文件已存在");
					try {
						FileUtils.moveFile(srcFile, desFile);
					} catch (IOException e) {
						throw new RuntimeException(e);
					}
				});
		return new ResResult("success");
	}
	
	@Override
	public void afterPropertiesSet() throws Exception {
		//自动创建文件夹
		String[] basePaths = mosClientProperties.getBasePaths();
		if (basePaths != null) {
			for (String basePath : basePaths) {
				File file = new File(basePath);
				if (!file.exists()) {
					file.mkdirs();
				}
			}
		}
	}
	
}
