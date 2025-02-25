package GUI;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import javax.swing.JOptionPane;

import burp.BurpExtender;
import burp.Commons;
import burp.IBurpExtenderCallbacks;
import burp.IExtensionHelpers;
import burp.IHttpRequestResponse;
import domain.DomainPanel;
import title.LineEntry;
import title.TitlePanel;

//////////////////ThreadGetTitle block/////////////
//no need to pass BurpExtender object to these class, IBurpExtenderCallbacks object is enough 
public class ThreadRunner extends Thread{
	public static final int ChangeService = 4;
	public static final int ChangeHostInService = 3;
	public static final int ChangeHostInHeader = 2;
	public static final int ChangeCancel = 1;
	public static final int ChangeHelp =0;
	public static final int ChangeClose = -1;
	
	private IHttpRequestResponse messageInfo;
	private List<RunnerProducer> plist;

	private static IBurpExtenderCallbacks callbacks = BurpExtender.getCallbacks();//静态变量，burp插件的逻辑中，是可以保证它被初始化的。;
	public PrintWriter stdout = new PrintWriter(callbacks.getStdout(), true);
	public PrintWriter stderr = new PrintWriter(callbacks.getStderr(), true);
	public IExtensionHelpers helpers = callbacks.getHelpers();
	private RunnerGUI runnerGUI;
	int changeType;
	private boolean AllProductorFinished;
	private boolean changeraw;

	public ThreadRunner(RunnerGUI runnerGUI, IHttpRequestResponse messageInfo) {
		this.runnerGUI = runnerGUI;
		this.messageInfo = messageInfo;
		this.changeType = fetchChangeType();
	}
	
	public ThreadRunner(RunnerGUI runnerGUI, IHttpRequestResponse messageInfo,int changeType) {
		this.runnerGUI = runnerGUI;
		this.messageInfo = messageInfo;
		this.changeType = changeType;
	}

	@Deprecated
	public String getKeywordFromUI() {
		String responseKeyword = JOptionPane.showInputDialog("Response Keyword", null);
		while(responseKeyword.trim().equals("")){
			responseKeyword = JOptionPane.showInputDialog("Response Keyword", null);
		}
		responseKeyword = responseKeyword.trim();
		return responseKeyword;
	}
	
	/**
	 * 情况一：只修改host，那么protocol、port都不变，适合查找相同服务的IP、Name
	 * 情况二：修改httpService，那么protocol、host、port修改，适合验证cookie\token对于站点的有效性!
	 * 情况三：目标httpService不变，只修改Header中的host字段，用于检测Nginx或者gateway的防护弱点。
	 * @return Help=0 CANCEL=1 ....,即数组的index。
	 */
	public static int fetchChangeType() {
		Object[] options = { "Help","CANCEL","Host In Header","Host Of HttpService","HttpService"};
		int user_input = JOptionPane.showOptionDialog(null, "Which Part Do You Want To Repalce?", "Chose Replace Part",
		JOptionPane.DEFAULT_OPTION, JOptionPane.WARNING_MESSAGE,
		null, options, options[0]);
		if (user_input ==0) {
			try {
				Commons.browserOpen("https://github.com/bit4woo/domain_hunter_pro/blob/master/Help.md", null);
			} catch (Exception e) {
				e.printStackTrace();
			}
			user_input = fetchChangeType();
		}
		return user_input;
	}
	public static boolean changeRaw() {
		int result = JOptionPane.showConfirmDialog(null,"Do you want to change [Host in header] when change service?","Chose",
				JOptionPane.YES_NO_OPTION,
				JOptionPane.QUESTION_MESSAGE);
		if (result == JOptionPane.YES_OPTION) {
			return true;
		}else {
			return false;
		}
	}
	
	public static HashSet<String> getDomainsForBypassCheck(){
		HashSet<String> resultSet = new HashSet<String>();
		
		HashSet<String> tmpSet = new HashSet<String>();//所有子域名列表
		tmpSet.addAll(DomainPanel.getDomainResult().getSubDomainSet());
		
		HashSet<String> unreachableSet = new HashSet<String>();
		Collection<LineEntry> entries = TitlePanel.getTitleTableModel().getLineEntries().values();
		for (LineEntry entry:entries) {
			if (entry.getEntryType().equals(LineEntry.EntryType_DNS) || entry.getStatuscode() == 403) {
				unreachableSet.add(entry.getHost());
			}
			tmpSet.remove(entry.getHost());
		}//删除了title中成功请求的域名
		
		tmpSet.addAll(unreachableSet);//添加了请求失败、只有解析、状态403的域名
		
		//		for (String item:tmpSet) {//移除IP，这步骤是否需要？
		//			if (Commons.isValidDomain(item)) {
		//				resultSet.add(item);
		//			}
		//		}
		//		
		//		return resultSet;
		return tmpSet;
	}
	
	@Override
	public void run(){
		runnerGUI.lblStatus.setText("running");
		BlockingQueue<LineEntry> lineEntryQueue = new LinkedBlockingQueue<LineEntry>();//use to store domains
		BlockingQueue<String> domainQueue = new LinkedBlockingQueue<String>();
		lineEntryQueue.addAll(TitlePanel.getTitleTableModel().getLineEntries().values());
		domainQueue.addAll(getDomainsForBypassCheck());
		
		if (changeType == ChangeCancel || changeType == ChangeClose ){//用户选了cancel（2）或者点击了关闭（-1）
			return;
		}
		
		if (changeType == ChangeHostInHeader) {
			stdout.println("~~~~~~~~~~~~~Start threading Runner~~~~~~~~~~~~~ total task number: "+domainQueue.size());
		}else {
			stdout.println("~~~~~~~~~~~~~Start threading Runner~~~~~~~~~~~~~ total task number: "+lineEntryQueue.size());
		}
		
		if (changeType == ChangeHostInHeader) {
			changeraw = true;
		}else {
			changeraw = changeRaw();
		}
		
		plist = new ArrayList<RunnerProducer>();

		for (int i=0;i<=50;i++) {
			RunnerProducer p = new RunnerProducer(runnerGUI.getRunnerTableModel(),lineEntryQueue,domainQueue,
					messageInfo,changeType,changeraw,i);
			p.setDaemon(true);//将子线程设置为守护线程，会随着主线程的结束而立即结束
			p.start();
			plist.add(p);
		}

		for (RunnerProducer p:plist) {
			try {
				p.join();
				//让主线程等待各个子线程执行完成，才会结束。
				//https://www.cnblogs.com/zheaven/p/12054044.html
			} catch (InterruptedException e) {
				stdout.println("force stop received");
				e.printStackTrace();
				break;//必须跳出循环，否则只是不再等待其中的一个线程，还会继续等待其他线程
			}
		}

		stdout.println("all producer threads finished");
		AllProductorFinished = true;
		runnerGUI.lblStatus.setText("finished");
		return;
	}

	boolean isAllProductorFinished(){
		return AllProductorFinished;
	}

	public void stopThreads() {
		if (plist != null) {
			for (RunnerProducer p:plist) {
				p.stopThread();
			}
			stdout.println("~~~~~~~~~~~~~All stop message sent! wait them to exit~~~~~~~~~~~~~");
		}
	}

	public static void main(String[] args){
		System.out.println(fetchChangeType());
	}
}

