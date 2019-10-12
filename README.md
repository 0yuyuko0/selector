## 使用
### Channel
```java
class Test{
    void test(){
        Channelnel<Integer> channel = new Channelnel<>();
        new Thread(()->System.out.println(channel.read())).start();
        channel.write(1);
        new Thread(()->channel.write(2)).start();
        System.out.println(channel.read());
    }
}
```
```java
1
2
```
###Select
```java
class Test{
    void test(){
        Channel<Integer> channel1 = new Channel<>();
        Channel<Integer> channel2 = new Channel<>();
        
        new Thread(()->System.out.println(channel2.read())).start();
        new Thread(()->channel1.write(1)).start();
        
        TimeUnit.MILLISECONDS.sleep(500);
        
        Selector selector = Selector.open();
        selector
                .register(channel1,SelectionKey.write(2))
                .register(channel2,SelectionKey.read())
                .fallback(SelectionKey.fallback());
        SelectionKey key = selector.select();
        if(key.channel() == channel1)
            System.out.println(2);
        else if(key.channel() == channel2)
            System.out.println(1);
        else if(key.type() == SelectionKey.FALLBACK)
            System.out.println(3);
    }
}
```
```java
随机输出123的任意一个
```
#### 注意
本库是我的另一个库go-chan-and-select的改进版，这个库由于一些历史代码原因还未删除
等修改完代码后删除。