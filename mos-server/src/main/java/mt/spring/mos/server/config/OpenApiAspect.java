package mt.spring.mos.server.config;

import mt.common.currentUser.UserContext;
import mt.common.tkmapper.Filter;
import mt.spring.mos.server.annotation.OpenApi;
import mt.spring.mos.server.entity.MosServerProperties;
import mt.spring.mos.server.entity.po.AccessControl;
import mt.spring.mos.server.entity.po.Bucket;
import mt.spring.mos.server.entity.po.User;
import mt.spring.mos.server.service.AccessControlService;
import mt.spring.mos.server.service.BucketService;
import mt.utils.Assert;
import mt.utils.MyUtils;
import org.apache.commons.beanutils.ConvertUtils;
import org.apache.commons.lang3.StringUtils;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static mt.common.tkmapper.Filter.Operator.eq;

/**
 * @Author Martin
 * @Date 2020/6/3
 */
@Aspect
@Component
public class OpenApiAspect {
	@Autowired
	private MosServerProperties mosServerProperties;
	@Autowired
	private BucketService bucketService;
	@Autowired
	private UserContext userContext;
	@Autowired
	private AccessControlService accessControlService;
	
	@SuppressWarnings("unchecked")
	private <T> T getParameter(String name, Object[] args, Parameter[] parameters, HttpServletRequest request, Class<T> type) {
		for (int i = 0; i < parameters.length; i++) {
			if (parameters[i].getName().equals(name)) {
				return (T) ConvertUtils.convert(args[i], type);
			}
		}
		return (T) ConvertUtils.convert(request.getParameter(name), type);
	}
	
	@Before("@annotation(mt.spring.mos.server.annotation.OpenApi)")
	public void openApi(JoinPoint joinPoint) {
		ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
		assert attributes != null;
		HttpServletRequest request = attributes.getRequest();
		HttpServletResponse response = attributes.getResponse();
		
		Object[] args = joinPoint.getArgs();
		MethodSignature signature = (MethodSignature) joinPoint.getSignature();
		Method method = signature.getMethod();
		Parameter[] parameters = method.getParameters();
		User currentUser = (User) userContext.getCurrentUser();
		String sign = getParameter("sign", args, parameters, request, String.class);
		String bucketName = getParameter("bucketName", args, parameters, request, String.class);
		Assert.notBlank(bucketName, "未传入bucketName");
		
		//校验签名
		Bucket bucket;
		if (sign != null) {
			OpenApi openApi = method.getAnnotation(OpenApi.class);
			List<String> pathnameList = new ArrayList<>();
			String pathname = getParameter("pathname", args, parameters, request, String.class);
			String[] pathnames = getParameter("pathnames", args, parameters, request, String[].class);
			if (StringUtils.isNotBlank(openApi.pathnamePrefix())) {
				String prefix = openApi.pathnamePrefix();
				prefix = prefix.replace("{bucketName}", bucketName);
				pathname = request.getRequestURI().substring(prefix.length());
			}
			if (StringUtils.isNotBlank(pathname)) {
				pathnameList.add(pathname);
			} else if (MyUtils.isNotEmpty(pathnames)) {
				pathnameList.addAll(Arrays.asList(pathnames));
			} else {
				throw new IllegalStateException("pathname不能为空");
			}
			for (String s : pathnameList) {
				Assert.state(!s.contains(".."), "非法路径" + s);
			}
			
			String names = StringUtils.join(pathnameList, ",");
			Long openId = getParameter("openId", args, parameters, request, Long.class);
			Assert.notNull(openId, "openId不能为空");
			if (mosServerProperties.getIsCheckSign()) {
				accessControlService.checkSign(openId, sign, names, bucketName);
			}
			AccessControl accessControl = accessControlService.findById(openId);
			Long bucketId = accessControl.getBucketId();
			bucket = bucketService.findById(bucketId);
		} else if (currentUser != null) {
			List<Filter> filters = new ArrayList<>();
			filters.add(new Filter("bucketName", eq, bucketName));
			filters.add(new Filter("userId", eq, currentUser.getId()));
			bucket = bucketService.findOneByFilters(filters);
		} else {
			assert response != null;
			response.setStatus(HttpStatus.FORBIDDEN.value());
			throw new IllegalStateException("没有权限访问");
		}
		Assert.notNull(bucket, "bucket不存在");
		for (int i = 0; i < parameters.length; i++) {
			if (parameters[i].getType().isAssignableFrom(Bucket.class)) {
				BeanUtils.copyProperties(bucket, args[i]);
			}
		}
	}
}
