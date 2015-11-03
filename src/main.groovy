#!/usr/bin/env groovy
import org.codehaus.groovy.control.CompilerConfiguration
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

import java.util.regex.Matcher
import java.util.regex.Pattern

@Grapes( @Grab('org.jsoup:jsoup:1.6.1'))
class Request {
    String url;
    public Request clone(){
        def r = new Request()
        r.url = this.url == null ? null : this.url.concat("")
        return r
    }
}
class Response {
    String content;

    Response clone(){
        def r = new Response()
        r.content = this.content == null ? null : this.content.concat("")
        return r
    }
}
class Message {
    Request request;
    Response response;
    Object results;
    public Message clone(){
        def r = new Message()
        r.response = this.response == null ? null : this.response.clone()
        r.request = this.request == null ? null : this.request.clone()
        r.results = this.results
        return r
    }
}



def cli = new CliBuilder()
def options = cli.parse(args)

if(options.arguments().size()<2){
    println "You must pass the name of a crawlerfile and a start input"
    println "e.g."
    println "./crawler.groovy tripadvisor.crawlerfile http://www.tripadvisor.com/AllLocations-g1-Places-World.html"
}

def crawlerfilename = options.arguments().first()
def crawlerStartInputUrl = options.arguments().get(1)
def crawlerStartInput = new Message()
crawlerStartInput.request = new Request()
crawlerStartInput.request.url = crawlerStartInputUrl


class Emitted {
    Closure sendTo
    public void to(Closure sendTo){
        this.sendTo = sendTo
    }
    public List<Message> build(Message input){
        def res = new ArrayList<>()
        res.add(input.clone())
        return res
    }

}

class LinkEmitted extends  Emitted {

    public List<Message> build(Message input){
        def res = new ArrayList<>()
        input.results.each { i ->
            Message msg = new Message()
            msg.request = new Request()
            msg.request.url = i
            res.add(msg)
        }
        return res
    }
}

class ObjectEmitted extends Emitted {
    Map<Object, Closure> fields;

    public Emitted fields(Map) {
        this.fields = fields
        return this
    }

    public List<Message> build(Message input){
        Map<Object, Object> res = new LinkedHashMap()
        this.fields.each { k, v ->
            res.put(k, v.call(input.results))
        }
        Message ret = input.clone()
        ret.results = res
        List<Message> rets = new ArrayList<>()
        rets.add(ret)
        return rets
    }
}

class Emitter {
    Emitted emitted
    Closure action = {
        println "Default emitter action closure call"
        return it
    }
    Emitter nextEmitter
    Emitter previousEmitter

    public Message getValue(Message input){
        Message output = action(input)
        if(nextEmitter != null){
            output = nextEmitter.getValue(output)
        }
        return output
    }

    public Emitter getLastEmitter(){
        if(this.nextEmitter == null){
            return this
        }
        return this.nextEmitter.getLastEmitter()
    }
    public Emitter getFirstEmitter(){
        if(this.previousEmitter == null){
            return this;
        }
        return this.previousEmitter.getFirstEmitter();
    }
}

abstract class Step {
    String name
    Emitter currentEmitter = null
    List<Emitter> outputs = new ArrayList<>()

    public Emitted emit(Closure emittedFactory){
        if(currentEmitter == null){
            currentEmitter = new Emitter()
        }
        currentEmitter.emitted = emittedFactory()
        outputs.add(currentEmitter)
        def emitted = currentEmitter.emitted
        currentEmitter = null
        return emitted
    }
    public void recipe(Closure c){
        c.delegate = this
        c.call()
    }

    public void apply (closure){
        Emitter e = new Emitter()
        e.action = closure
        if(currentEmitter != null){
            e.previousEmitter = currentEmitter
            currentEmitter.nextEmitter = e
        }
        currentEmitter = e
    }


    public void attributeSelector(String selector){
        apply {
            what ->
                println "attributeSelector emitter action closure call with arg : " + selector
                List<Object> obj = new ArrayList<>()
                what.results.each { r ->
                    if(r != null) {
                        Document doc = Jsoup.parse(r)
                        if (doc.body().children() != 0) {
                            doc.body().children().each { e->
                                if (e != null) {
                                    obj.add(e.attr(selector))
                                }
                            }
                        }
                    }
                }
                def ret = ((Message)what).clone()
                ret.results = obj
                return ret
        }
    }

    public void cssSelector(String selector){
        apply {
            what ->
                println "cssSelector emitter action closure call with arg : " + selector
                List<Object> obj = new ArrayList<>()
                what.results.each { r ->
                    if(r != null) {
                        Document doc = Jsoup.parse(r)
                        doc.select(selector).each { e ->
                            obj.add(e.outerHtml())
                        };
                    }
                }
                def ret = ((Message)what).clone()
                ret.results = obj
                return ret
        }
    }

    public void regexp(String regexp){
        apply {
            what ->
                println "regexp emitter action closure call with arg : " + regexp
                List<Object> obj = new ArrayList<>()
                what.results.each { r ->
                    Pattern p = Pattern.compile(regexp);
                    Matcher m = p.matcher(r);
                    while(m.find()){
                        obj.add(m.group())
                    }
                }
                def ret = ((Message)what).clone()
                ret.results = obj
                return ret
        }
    }

    public void extractCrawledUrl(){
        apply {
            what ->
                println "extractCrawledUrl emitter action closure call "
                List<Object> obj = new ArrayList<>()
                what.results.each {
                    obj.add(what.request.url)
                }

                def ret = ((Message)what).clone()
                ret.results = obj
                return ret
        }
    }

    public void toFirstElement(){
        apply {
            what ->
                println "toFirstElement emitter action closure call"
                def ret = ((Message)what).clone()
                ret.results = what.results.get(0)
                return ret
        }
    }

    public void textCssSelector(String selector){
        apply {
            what ->
                println "textCssSelector emitter action closure call with arg : " + selector
                List<Object> obj = new ArrayList<>()
                what.results.each { r ->
                    if(r != null) {
                        Document doc = Jsoup.parse(r)
                        doc.select(selector).each { e ->
                            obj.add(e.text())
                        };
                    }
                }
                def ret = ((Message)what).clone()
                ret.results = obj
                return ret
        }
    }

    public void prepend(String pre){
        apply {
            what ->
                println "prepend emitter action closure call with arg : " + pre
                List<Object> obj = new ArrayList<>()
                what.results.each { r ->
                    if(r != null){
                        obj.add(pre.concat(r.toString()))
                    }
                }
                def ret = ((Message)what).clone()
                ret.results = obj
                return ret
        }
    }


    public abstract Object exec(Message s)
}

class Download extends Step {

    public Object exec(Message input){
        println "downloading url " + input.request.url
        Message m = (Message) input;
        Message result = input.clone();
        result.response = new Response()
        result.response.content = new URL(m.request.url).getText()
        return result
    }
}

class Parse extends Step {
    public Object exec(Message input){
        println "parsing content " + input.response.content
        List<Object> array = new ArrayList()
        array.add(input == null || input.response == null || input.response.content == null ? "" : input.response.content.concat(""))
        Message r = input.clone()
        r.results = array
        return r;
    }
}

abstract class Crawlerfile extends Script {
    LinkedHashMap<String, Object> download = new LinkedHashMap<>()
    LinkedHashMap<String, Object> parse = new LinkedHashMap<>()

    def getStartStepClosure = {}
    def link = { return new LinkEmitted() }
    def page = { return new Emitted() }
    def object = { return new ObjectEmitted() }

    public Step getStartStep(){
        return getStartStepClosure.call()
    }

    public Download download(String name){
        def step = download.get(name);
        if(step == null ){
            step = new Download()
            step.name = name
            download.put(name, step)
        }
        return step
    }

    public Parse parse(String name) {
        def step = parse.get(name);
        if(step == null ){
            step = new Parse()
            step.name = name
            parse.put(name, step)
        }
        return step
    }

    public Closure start(Closure getStartStep) {
        getStartStepClosure = getStartStep
    }

}

def crawlerConfig = new CompilerConfiguration();
crawlerConfig.scriptBaseClass = Crawlerfile.class.getName();

def shell = new GroovyShell(this.class.classLoader, crawlerConfig)

def crawlerfileContents = new File(crawlerfilename).getText('UTF-8')

def crawlerfile = shell.evaluate(crawlerfileContents + "\nreturn this\n")

public class StepExecution{
    Step step
    Message input
}

public class CrawlerfileExecutor {

    public void run(Crawlerfile crawlerfile, Object crawlerStartInput) {
        StepExecution exec = new StepExecution()
        exec.input = crawlerStartInput
        exec.step = crawlerfile.getStartStep()
        LinkedList<StepExecution> steps = new LinkedList<>()
        steps.push(exec)
        while(steps.size() > 0){
            runStep(steps.pop()).each {
                s -> steps.push(s)
            }
        }
    }
    public static List<StepExecution> runStep(StepExecution current){
        Message output = current.step.exec(current.input)
        def res = new ArrayList()
        List<Emitter> emitters = current.step.getOutputs()
        for(Emitter e : emitters){
            List<StepExecution> stepExecutions = new ArrayList<>()
            def msg = e.getFirstEmitter().getValue(output)
            def step = e.getLastEmitter().getEmitted().sendTo();
            List<Message> lmsg = e.getLastEmitter().emitted.build(msg)
            lmsg.each { msgitem ->
                StepExecution exec = new StepExecution()
                exec.input = msgitem
                exec.step = step
                stepExecutions.add(exec)
            }
            res.addAll(stepExecutions)
        }
        return res
    }
}

new CrawlerfileExecutor().run(crawlerfile, crawlerStartInput)